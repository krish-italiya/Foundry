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
import java.util.Optional;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
                logService,
                JsonMapper.builder().build(),
                projectRepository,
                temporaryDirectory.resolve("workspaces").toString(),
                "",
                60);

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
}
