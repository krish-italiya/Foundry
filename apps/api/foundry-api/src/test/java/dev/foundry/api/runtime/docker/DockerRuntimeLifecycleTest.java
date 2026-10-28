package dev.foundry.api.runtime.docker;

import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DockerRuntimeLifecycleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void containerParserOnlyAcceptsFoundryNamesWithCanonicalDeploymentUuid() {
        UUID id = UUID.randomUUID();

        assertThat(DockerRuntimeAdapter.deploymentIdFromContainerName("foundry-deployment-" + id))
                .contains(id);
        assertThat(DockerRuntimeAdapter.deploymentIdFromContainerName("/foundry-deployment-" + id))
                .contains(id);
        assertThat(DockerRuntimeAdapter.deploymentIdFromContainerName("other-service-" + id)).isEmpty();
        assertThat(DockerRuntimeAdapter.deploymentIdFromContainerName("foundry-deployment-not-a-uuid")).isEmpty();
        assertThat(DockerRuntimeAdapter.deploymentIdFromContainerName(null)).isEmpty();
    }

    @Test
    void retriesFailedContainerRemovalAndKeepsImageUntilContainerIsGone() {
        ScriptedDockerRuntimeAdapter runtime = new ScriptedDockerRuntimeAdapter(temporaryDirectory);
        runtime.cleanupAfterRestart(); // Enable scheduled retries only after startup reconciliation.
        UUID deploymentId = UUID.randomUUID();
        runtime.containerRemovalResults.add(new DockerRuntimeAdapter.CommandResult(
                1, "Cannot connect to the Docker daemon"));

        runtime.stop(deploymentId);

        assertThat(runtime.containerRemovalAttempts()).isEqualTo(1);
        assertThat(runtime.imageRemovalAttempts()).isZero();
        assertThat(runtime.processAlive(deploymentId)).contains(true);

        runtime.containerRemovalResults.add(new DockerRuntimeAdapter.CommandResult(0, ""));

        runtime.retryPendingCleanupAndRemoveOrphans();

        assertThat(runtime.containerRemovalAttempts()).isEqualTo(2);
        assertThat(runtime.imageRemovalAttempts()).isEqualTo(1);
        assertThat(runtime.commands).anySatisfy(command ->
                assertThat(command).containsExactly("container", "ls", "--all",
                        "--format={{.ID}}|{{.Names}}", "--filter", "name=foundry-deployment-"));
        assertThat(runtime.commands).anySatisfy(command ->
                assertThat(command).containsExactly("image", "ls", "--all",
                        "--format={{.Repository}}:{{.Tag}}", "--filter", "reference=foundry-deployment-*"));
    }

    @Test
    void startupReconciliationRemovesOnlyDeterministicFoundryContainersAndTheirImages() {
        ScriptedDockerRuntimeAdapter runtime = new ScriptedDockerRuntimeAdapter(temporaryDirectory);
        UUID deploymentId = UUID.randomUUID();
        runtime.containerListing = "abc123|foundry-deployment-" + deploymentId + System.lineSeparator();

        runtime.reconcileOrphanedDockerResources();

        assertThat(runtime.commands).contains(
                List.of("rm", "--force", "foundry-deployment-" + deploymentId),
                List.of("image", "rm", "--force", "foundry-deployment-" + deploymentId));
        assertThat(runtime.containerRemovalAttempts()).isEqualTo(1);
        assertThat(runtime.imageRemovalAttempts()).isEqualTo(1);
    }

    private static final class ScriptedDockerRuntimeAdapter extends DockerRuntimeAdapter {
        private final List<List<String>> commands = new ArrayList<>();
        private final Deque<CommandResult> containerRemovalResults = new ArrayDeque<>();
        private String containerListing = "";
        private String imageListing = "";
        private int containerRemovalAttempts;
        private int imageRemovalAttempts;

        private ScriptedDockerRuntimeAdapter(Path root) {
            super(mock(DeploymentLogService.class), "docker", "512m", 1.0, 60,
                    root.resolve("contexts").toString(),
                    root.resolve("workspaces").toString(),
                    "http://localhost:8080");
        }

        @Override
        CommandResult runDockerCommand(
                List<String> args,
                Duration timeout,
                UUID deploymentId,
                DeploymentLogStage stage,
                boolean logOutput,
                boolean requireSuccess) throws IOException {
            commands.add(List.copyOf(args));
            if (args.size() >= 2 && "rm".equals(args.getFirst()) && "--force".equals(args.get(1))) {
                containerRemovalAttempts++;
                return containerRemovalResults.isEmpty()
                        ? new CommandResult(0, "")
                        : containerRemovalResults.removeFirst();
            }
            if (args.size() >= 3 && "image".equals(args.getFirst()) && "rm".equals(args.get(1))) {
                imageRemovalAttempts++;
                return new CommandResult(0, "");
            }
            if (args.size() >= 2 && "container".equals(args.getFirst()) && "ls".equals(args.get(1))) {
                return new CommandResult(0, containerListing);
            }
            if (args.size() >= 2 && "image".equals(args.getFirst()) && "ls".equals(args.get(1))) {
                return new CommandResult(0, imageListing);
            }
            return new CommandResult(0, "");
        }

        private int containerRemovalAttempts() {
            return containerRemovalAttempts;
        }

        private int imageRemovalAttempts() {
            return imageRemovalAttempts;
        }
    }
}