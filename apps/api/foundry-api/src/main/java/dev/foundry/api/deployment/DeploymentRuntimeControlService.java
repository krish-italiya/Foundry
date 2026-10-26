package dev.foundry.api.deployment;

import dev.foundry.api.deployment.dto.DeploymentResponse;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import dev.foundry.api.runtime.local.LocalProcessRuntimeAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates an explicit user-requested stop for a currently running local deployment.
 */
@Service
public class DeploymentRuntimeControlService {

    private static final Logger log = LoggerFactory.getLogger(DeploymentRuntimeControlService.class);

    private final DeploymentService deploymentService;
    private final DeploymentExecutionService executionService;
    private final ObjectProvider<LocalProcessRuntimeAdapter> runtimeAdapters;
    private final DeploymentLogService deploymentLogService;

    public DeploymentRuntimeControlService(
            DeploymentService deploymentService,
            DeploymentExecutionService executionService,
            ObjectProvider<LocalProcessRuntimeAdapter> runtimeAdapters,
            DeploymentLogService deploymentLogService) {
        this.deploymentService = deploymentService;
        this.executionService = executionService;
        this.runtimeAdapters = runtimeAdapters;
        this.deploymentLogService = deploymentLogService;
    }

    public StopResult stop(UUID deploymentId) {
        Optional<DeploymentResponse> found = deploymentService.findById(deploymentId);
        if (found.isEmpty()) {
            return new StopResult(StopOutcome.NOT_FOUND, null);
        }

        DeploymentResponse current = found.get();
        if (current.status() != DeploymentStatus.SUCCEEDED
                || current.runtimeState() != RuntimeState.RUNNING) {
            return new StopResult(StopOutcome.NOT_RUNNING, current);
        }

        LocalProcessRuntimeAdapter runtime = runtimeAdapters.getIfAvailable();
        if (runtime == null) {
            return new StopResult(StopOutcome.RUNTIME_UNAVAILABLE, current);
        }

        Optional<Boolean> changed = executionService.tryMarkRuntimeState(
                deploymentId, RuntimeState.STOPPED);
        if (changed.isEmpty()) {
            return new StopResult(StopOutcome.NOT_FOUND, null);
        }
        if (!changed.get()) {
            return new StopResult(
                    StopOutcome.NOT_RUNNING,
                    deploymentService.findById(deploymentId).orElse(current));
        }

        try {
            // stop() removes the active route/process mapping and cleans up the runtime workspace.
            runtime.stop(deploymentId);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Runtime stop cleanup raised an exception for deployment {}",
                    deploymentId, cleanupFailure);
            appendLogSafely(
                    deploymentId,
                    DeploymentLogSeverity.WARN,
                    "Runtime was marked STOPPED, but cleanup reported "
                            + cleanupFailure.getClass().getSimpleName() + ".");
        }

        appendLogSafely(
                deploymentId,
                DeploymentLogSeverity.INFO,
                "Application runtime stopped by user request.");

        return new StopResult(
                StopOutcome.STOPPED,
                deploymentService.findById(deploymentId).orElse(current));
    }

    private void appendLogSafely(
            UUID deploymentId,
            DeploymentLogSeverity severity,
            String message) {
        try {
            deploymentLogService.append(
                    deploymentId,
                    DeploymentLogStage.RUNTIME,
                    DeploymentLogStream.DEPLOYMENT,
                    severity,
                    message);
        } catch (RuntimeException loggingFailure) {
            log.warn("Could not append runtime stop log for deployment {}", deploymentId,
                    loggingFailure);
        }
    }

    public enum StopOutcome {
        STOPPED,
        NOT_FOUND,
        NOT_RUNNING,
        RUNTIME_UNAVAILABLE
    }

    public record StopResult(StopOutcome outcome, DeploymentResponse deployment) {
    }
}
