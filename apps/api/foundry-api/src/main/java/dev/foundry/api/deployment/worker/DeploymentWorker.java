package dev.foundry.api.deployment.worker;

import dev.foundry.api.deployment.DeploymentExecutionService;
import dev.foundry.api.deployment.DeploymentStatus;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import dev.foundry.api.runtime.RuntimeAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@Service
public class DeploymentWorker {

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(10);

    private static final String RUNTIME_UNAVAILABLE_REASON =
            "Deployment execution is disabled because no runtime adapter "
                    + "is configured.";

    private static final String HEALTH_FAILURE_REASON =
            "The application health check did not pass before timeout.";

    private static final String RUNTIME_FAILURE_REASON =
            "The local runtime failed to start or pass its health check.";

    private final DeploymentExecutionService executionService;
    private final DeploymentLogService deploymentLogService;
    private final ObjectProvider<RuntimeAdapter> runtimeAdapters;

    public DeploymentWorker(
            DeploymentExecutionService executionService,
            DeploymentLogService deploymentLogService,
            ObjectProvider<RuntimeAdapter> runtimeAdapters) {
        this.executionService = executionService;
        this.deploymentLogService = deploymentLogService;
        this.runtimeAdapters = runtimeAdapters;
    }

    public boolean processQueuedDeployment(UUID deploymentId) {
        if (!executionService.claimQueued(deploymentId)) {
            return false;
        }

        appendLog(
                deploymentId,
                DeploymentLogStage.QUEUE,
                DeploymentLogSeverity.INFO,
                "Worker claimed the queued deployment."
        );

        RuntimeAdapter runtime = runtimeAdapters.getIfAvailable();

        if (runtime == null) {
            appendLog(
                    deploymentId,
                    DeploymentLogStage.BUILD,
                    DeploymentLogSeverity.WARN,
                    "Execution is disabled because no runtime adapter "
                            + "is configured."
            );

            executionService.markFailed(
                    deploymentId,
                    RUNTIME_UNAVAILABLE_REASON
            );

            appendLog(
                    deploymentId,
                    DeploymentLogStage.WORKER,
                    DeploymentLogSeverity.ERROR,
                    "Deployment marked FAILED: runtime adapter unavailable."
            );

            return true;
        }

        try {
            appendLog(
                    deploymentId,
                    DeploymentLogStage.BUILD,
                    DeploymentLogSeverity.INFO,
                    "Using the prebuilt trusted sample application; "
                            + "no repository checkout is performed."
            );

            executionService.advanceTo(
                    deploymentId,
                    DeploymentStatus.STARTING
            );

            URI applicationUrl = runtime.start(deploymentId);

            appendLog(
                    deploymentId,
                    DeploymentLogStage.STARTUP,
                    DeploymentLogSeverity.INFO,
                    "Started the sample application at " + applicationUrl
            );

            executionService.advanceTo(
                    deploymentId,
                    DeploymentStatus.HEALTH_CHECK
            );

            appendLog(
                    deploymentId,
                    DeploymentLogStage.HEALTH_CHECK,
                    DeploymentLogSeverity.INFO,
                    "Checking the application's /health endpoint."
            );

            boolean healthy = runtime.awaitHealthy(
                    deploymentId,
                    HEALTH_TIMEOUT
            );

            if (!healthy) {
                return failRuntime(
                        deploymentId,
                        runtime,
                        HEALTH_FAILURE_REASON
                );
            }

            appendLog(
                    deploymentId,
                    DeploymentLogStage.HEALTH_CHECK,
                    DeploymentLogSeverity.INFO,
                    "Health check passed: HTTP 200 and status UP."
            );

            executionService.markSucceeded(
                    deploymentId,
                    applicationUrl.toString()
            );

            return true;

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();

            return failRuntime(
                    deploymentId,
                    runtime,
                    "Deployment processing was interrupted."
            );

        } catch (Exception failure) {
            String detail = failure.getClass().getSimpleName();

            if (failure.getMessage() != null
                    && !failure.getMessage().isBlank()) {
                detail += ": " + failure.getMessage();
            }

            return failRuntime(
                    deploymentId,
                    runtime,
                    RUNTIME_FAILURE_REASON + " Cause: " + detail
            );
        }
    }

    private boolean failRuntime(
            UUID deploymentId,
            RuntimeAdapter runtime,
            String failureReason) {

        String finalFailureReason = failureReason;

        try {
            runtime.stop(deploymentId);
        } catch (RuntimeException cleanupFailure) {
            finalFailureReason += " Runtime cleanup failed: "
                    + cleanupFailure.getClass().getSimpleName();

            if (cleanupFailure.getMessage() != null
                    && !cleanupFailure.getMessage().isBlank()) {
                finalFailureReason += ": "
                        + cleanupFailure.getMessage();
            }
        }

        executionService.markFailed(
                deploymentId,
                finalFailureReason
        );

        appendLog(
                deploymentId,
                DeploymentLogStage.WORKER,
                DeploymentLogSeverity.ERROR,
                "Deployment failed: " + finalFailureReason
        );

        return true;
    }

    private void appendLog(
            UUID deploymentId,
            DeploymentLogStage stage,
            DeploymentLogSeverity severity,
            String message) {

        deploymentLogService.append(
                deploymentId,
                stage,
                DeploymentLogStream.DEPLOYMENT,
                severity,
                message
        ).orElseThrow(() -> new IllegalStateException(
                "Deployment disappeared while processing it: "
                        + deploymentId
        ));
    }
}