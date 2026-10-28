package dev.foundry.api.deployment.build;

import dev.foundry.api.deployment.log.DeploymentLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuilderContainerRunnerTest {

    @TempDir
    Path temporaryDirectory;

    private final DockerCommandClient docker = mock(DockerCommandClient.class);
    private final DeploymentLogService logService = mock(DeploymentLogService.class);

    @Test
    void builderContainerUsesConstrainedEphemeralWorkspaceWithoutHostMounts() {
        BuilderContainerRunner runner = newRunner();
        UUID deploymentId = UUID.randomUUID();

        List<String> arguments = runner.createContainerArguments(
                deploymentId,
                BuilderContainerRunner.containerName(deploymentId),
                "node:22-bookworm-slim");

        assertThat(arguments).contains(
                "--memory", "3g",
                "--memory-swap", "3g",
                "--cpus", "2.0",
                "--pids-limit", "256",
                "--security-opt", "no-new-privileges:true",
                "--cap-drop", "ALL",
                "--network", "bridge",
                "--read-only",
                "--tmpfs", "/tmp:rw,exec,nosuid,size=2g",
                "--tmpfs", "/workspace:rw,exec,nosuid,size=4g");
        assertThat(arguments).doesNotContain("--mount", "--volume", "--publish", "-p");
        assertThat(String.join(" ", arguments)).doesNotContain("/var/run/docker.sock");
    }

    @Test
    void translatesWindowsNpmCommandToContainerExecutable() throws IOException {
        assertThat(BuilderContainerRunner.translateCommand(List.of("npm.cmd", "ci")))
                .containsExactly("npm", "ci");
    }

    @Test
    void translatesMavenWrapperToShellInvocationInsideContainer() throws IOException {
        assertThat(BuilderContainerRunner.translateCommand(List.of("mvnw.cmd", "-B", "verify")))
                .containsExactly("sh", "./mvnw", "-B", "verify");
    }

    @Test
    void rejectsArbitraryExecutableInsteadOfPassingThroughRepositoryCommand() {
        assertThatThrownBy(() -> BuilderContainerRunner.translateCommand(List.of("powershell.exe", "-Command", "whoami")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not allow");
        assertThatThrownBy(() -> BuilderContainerRunner.translateCommand(List.of("sh", "-c", "npm ci")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not allow");
    }

    @Test
    void acceptsOnlyCanonicalFoundryBuilderContainerNames() {
        UUID id = UUID.randomUUID();
        assertThat(BuilderContainerRunner.deploymentIdFromContainerName("foundry-builder-" + id))
                .contains(id);
        assertThat(BuilderContainerRunner.deploymentIdFromContainerName("foundry-builder-not-a-uuid"))
                .isEmpty();
        assertThat(BuilderContainerRunner.deploymentIdFromContainerName("other-" + id))
                .isEmpty();
    }

    @Test
    void startsBuilderCopiesCheckoutAndStripsGitMetadataBeforeBuild() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("checkout"));
        Files.createDirectory(workspace.resolve(".git"));
        Files.writeString(workspace.resolve("package.json"), "{\"name\":\"sample\"}");
        UUID deploymentId = UUID.randomUUID();
        when(docker.execute(anyList(), any(Duration.class), nullable(UUID.class), anyBoolean()))
                .thenReturn(new DockerCommandClient.Result(0, ""));

        BuilderContainerRunner runner = newRunner();
        runner.start(deploymentId, workspace, "NPM");

        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(docker, times(7)).execute(
                captor.capture(), any(Duration.class), nullable(UUID.class), anyBoolean());
        List<List<String>> commands = captor.getAllValues();

        assertThat(commands.get(0)).contains("image", "inspect", "node:22-bookworm-slim");
        assertThat(commands.get(1)).contains("--label", "dev.foundry.builder=true", "--read-only");
        assertThat(commands.get(2)).contains("cp");
        assertThat(commands.get(2).getLast()).isEqualTo(BuilderContainerRunner.containerName(deploymentId) + ":/workspace");
        assertThat(commands.get(3)).contains("rm", "-rf", "/workspace/.git");
        assertThat(commands.get(4)).contains("find", "/workspace", "-name", "mvnw", "-exec", "sed");
        assertThat(commands.get(5)).contains("find", "/workspace", "-name", "mvnw", "-exec", "chmod", "+x");
        assertThat(commands.get(6)).contains("chmod", "-R", "a+rwX", "/workspace");
    }

    @Test
    void runsBuildCommandAsUnprivilegedUserWithAnInContainerTimeout() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("repo"));
        Path service = Files.createDirectory(workspace.resolve("web"));
        UUID deploymentId = UUID.randomUUID();
        when(docker.execute(anyList(), any(Duration.class), nullable(UUID.class), anyBoolean()))
                .thenReturn(new DockerCommandClient.Result(0, ""));

        BuilderContainerRunner runner = newRunner();
        runner.start(deploymentId, workspace, "NPM");
        runner.run(deploymentId, service, List.of("npm.cmd", "ci"), Duration.ofSeconds(60), Map.of("CI", "true"));

        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<String>>) (Class<?>) List.class);
        verify(docker, times(8)).execute(
                captor.capture(), any(Duration.class), nullable(UUID.class), anyBoolean());
        List<String> command = captor.getAllValues().getLast();
        assertThat(command).contains("--user", "10001:10001", "--workdir", "/workspace/web",
                "--env", "CI=true", "timeout", "--signal=TERM", "--kill-after=5s", "60s", "npm", "ci");
    }

    @Test
    void reportsBuildTimeoutWhenContainerCommandReturnsTimeoutExitCode() throws Exception {
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("timeout-repo"));
        UUID deploymentId = UUID.randomUUID();
        when(docker.execute(anyList(), any(Duration.class), nullable(UUID.class), anyBoolean()))
                .thenReturn(new DockerCommandClient.Result(0, ""));
        when(docker.execute(
                org.mockito.ArgumentMatchers.argThat(args -> args.contains("timeout")),
                any(Duration.class), nullable(UUID.class), anyBoolean()))
                .thenReturn(new DockerCommandClient.Result(124, ""));

        BuilderContainerRunner runner = newRunner();
        runner.start(deploymentId, workspace, "NPM");

        assertThatThrownBy(() -> runner.run(
                deploymentId, workspace, List.of("npm.cmd", "ci"), Duration.ofSeconds(60), Map.of("CI", "true")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeded its 60-second timeout");

        runner.stop(deploymentId);
    }

    private BuilderContainerRunner newRunner() {
        return new BuilderContainerRunner(
                docker, logService,
                "maven:3.9-eclipse-temurin-25-noble",
                "node:22-bookworm-slim",
                "3g",
                2.0,
                "4g",
                600,
                true);
    }
}
