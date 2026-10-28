package dev.foundry.api.deployment.build;

import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.project.ProjectEntity;
import dev.foundry.api.project.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RepositoryBuildServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsRunnableJarWithMainClassManifest() throws IOException {
        Path jar = createJar("runnable.jar", "dev.foundry.samples.HelloServiceApplication");

        assertThat(RepositoryBuildService.isRunnableJar(jar)).isTrue();
    }

    @Test
    void rejectsJarWithoutMainClassManifest() throws IOException {
        Path jar = createJar("library.jar", null);

        assertThat(RepositoryBuildService.isRunnableJar(jar)).isFalse();
    }

    private Path createJar(String fileName, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        Path jar = temporaryDirectory.resolve(fileName);
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // A manifest-only archive is sufficient to test runnable-JAR detection.
        }
        return jar;
    }

    @Test
    void recognizesPackageLockAsReproducibleNpmLockfile() throws IOException {
        Files.createFile(temporaryDirectory.resolve("package-lock.json"));

        assertThat(RepositoryBuildService.hasReproducibleNpmLockfile(temporaryDirectory)).isTrue();
    }

    @Test
    void recognizesNpmShrinkwrapAsReproducibleNpmLockfile() throws IOException {
        Files.createFile(temporaryDirectory.resolve("npm-shrinkwrap.json"));

        assertThat(RepositoryBuildService.hasReproducibleNpmLockfile(temporaryDirectory)).isTrue();
    }

    @Test
    void allowsNpmProjectsWithoutLockfileToUseInstallFallback() {
        assertThat(RepositoryBuildService.hasReproducibleNpmLockfile(temporaryDirectory)).isFalse();
    }

    @Test
    void rejectsUntrustedProjectBeforeStartingRepositoryCommands() {
        UUID projectId = UUID.randomUUID();
        BuildCommandRunner commandRunner = mock(BuildCommandRunner.class);
        DeploymentLogService logService = mock(DeploymentLogService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                new ProjectEntity("untrusted", "https://github.com/example/untrusted-repo")));

        RepositoryBuildService service = new RepositoryBuildService(
                commandRunner,
                mock(BuilderContainerRunner.class),
                logService,
                JsonMapper.builder().build(),
                projectRepository,
                temporaryDirectory.resolve("workspaces").toString(),
                "",
                60,
                false);

        assertThatThrownBy(() -> service.build(
                UUID.randomUUID(),
                projectId,
                "https://github.com/example/untrusted-repo",
                "main",
                null))
                .isInstanceOf(RepositoryBuildException.class)
                .hasMessageContaining("not trusted");

        verifyNoInteractions(commandRunner, logService);
    }

    @Test
    void normalizesPublicHttpsGitHubRepositoryUrl() {
        assertThat(RepositoryBuildService.normalizeGitHubRepository(
                "https://github.com/OpenAI/example.git"))
                .isEqualTo("https://github.com/OpenAI/example");
        assertThat(RepositoryBuildService.normalizeGitHubRepository(
                "https://github.com/OpenAI/example/"))
                .isEqualTo("https://github.com/OpenAI/example");
    }

    @Test
    void rejectsNonGitHubRepositoryHosts() {
        assertThatThrownBy(() -> RepositoryBuildService.normalizeGitHubRepository(
                "https://example.com/owner/repo"))
                .isInstanceOf(RepositoryBuildException.class)
                .hasMessageContaining("GitHub");
    }

    @Test
    void rejectsGitHubUrlsWithCredentialsOrQueryParameters() {
        assertThatThrownBy(() -> RepositoryBuildService.normalizeGitHubRepository(
                "https://token@github.com/owner/repo"))
                .isInstanceOf(RepositoryBuildException.class);
        assertThatThrownBy(() -> RepositoryBuildService.normalizeGitHubRepository(
                "https://github.com/owner/repo?token=secret"))
                .isInstanceOf(RepositoryBuildException.class);
    }

    @Test
    void routesNpmScriptsThroughIsolatedBuilderWhenEnabled() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        BuildCommandRunner commandRunner = mock(BuildCommandRunner.class);
        BuilderContainerRunner builder = mock(BuilderContainerRunner.class);
        DeploymentLogService logService = mock(DeploymentLogService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(
                new ProjectEntity("trusted", "https://github.com/example/trusted", true)));

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> arguments = invocation.getArgument(2);
            if (arguments.size() > 1 && arguments.getFirst().equals("git")
                    && arguments.get(1).equals("clone")) {
                Path workspace = Path.of(arguments.getLast());
                Files.createDirectories(workspace);
                Files.writeString(workspace.resolve("package.json"),
                        "{\"name\":\"trusted-web\",\"scripts\":{\"test\":\"node test.js\",\"build\":\"vite build\",\"preview\":\"vite preview\"}}");
                Files.writeString(workspace.resolve("package-lock.json"), "{\"lockfileVersion\":3}");
                Files.writeString(workspace.resolve("app.js"), "console.log('not executed on host');");
            }
            return null;
        }).when(commandRunner).run(
                any(UUID.class), any(Path.class), anyList(), any(Duration.class), anyMap());

        doAnswer(invocation -> {
            Path serviceDirectory = invocation.getArgument(1);
            Files.createDirectories(serviceDirectory.resolve("dist"));
            Files.writeString(serviceDirectory.resolve("dist/index.html"), "<h1>built in container</h1>");
            return null;
        }).when(builder).copyNpmOutputs(any(UUID.class), any(Path.class));

        RepositoryBuildService service = new RepositoryBuildService(
                commandRunner,
                builder,
                logService,
                JsonMapper.builder().build(),
                projectRepository,
                temporaryDirectory.resolve("workspaces").toString(),
                "",
                60,
                true);

        BuiltArtifact artifact = service.build(
                deploymentId, projectId, "https://github.com/example/trusted", "main", null);

        assertThat(artifact.launchMode()).isEqualTo(BuiltArtifact.LaunchMode.VITE_PREVIEW);
        assertThat(artifact.artifactPath()).isEqualTo(artifact.serviceDirectory().resolve("dist"));
        assertThat(Files.isRegularFile(artifact.artifactPath().resolve("index.html"))).isTrue();

        verify(builder).start(deploymentId, artifact.workspaceDirectory(), "NPM");
        verify(builder, times(3)).run(
                org.mockito.ArgumentMatchers.eq(deploymentId),
                org.mockito.ArgumentMatchers.eq(artifact.serviceDirectory()),
                anyList(), any(Duration.class), anyMap());
        verify(builder).copyNpmOutputs(deploymentId, artifact.serviceDirectory());
        verify(builder).stop(deploymentId);
        // Host execution is limited to checkout and resolving the source revision.
        verify(commandRunner, times(2)).run(
                any(UUID.class), any(Path.class), anyList(), any(Duration.class), anyMap());
    }

}
