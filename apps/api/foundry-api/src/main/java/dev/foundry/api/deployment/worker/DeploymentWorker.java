package dev.foundry.api.deployment.worker;

import dev.foundry.api.deployment.DeploymentExecutionService;
import dev.foundry.api.deployment.DeploymentService;
import dev.foundry.api.deployment.DeploymentStatus;
import dev.foundry.api.deployment.build.BuiltArtifact;
import dev.foundry.api.deployment.build.RepositoryBuildService;
import dev.foundry.api.deployment.dto.DeploymentResponse;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import dev.foundry.api.runtime.RepositoryArtifactRuntime;
import dev.foundry.api.runtime.RuntimeAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

@Service
public class DeploymentWorker {

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(15);

    private static final String RUNTIME_UNAVAILABLE_REASON =
            "Deployment execution is disabled because no runtime adapter is configured.";

    private static final String HEALTH_FAILURE_REASON =
            "The application health check did not pass before timeout.";

    private static final String RUNTIME_FAILURE_REASON =
            "The local runtime failed to start or pass its health check.";

    private final DeploymentExecutionService executionService;
    private final DeploymentService deploymentService;
    private final DeploymentLogService deploymentLogService;
    private final ObjectProvider<RuntimeAdapter> runtimeAdapters;
    private final ObjectProvider<RepositoryBuildService> repositoryBuildServices;
    private final ObjectProvider<RepositoryArtifactRuntime> artifactRuntimeAdapters;
    private final boolean sampleModeEnabled;

    public DeploymentWorker(
            DeploymentExecutionService executionService,
            DeploymentService deploymentService,
            DeploymentLogService deploymentLogService,
            ObjectProvider<RuntimeAdapter> runtimeAdapters,
            ObjectProvider<RepositoryBuildService> repositoryBuildServices,
            ObjectProvider<RepositoryArtifactRuntime> artifactRuntimeAdapters,
            @Value("${foundry.runtime.local.sample-mode-enabled:false}") boolean sampleModeEnabled) {
        this.executionService = executionService;
        this.deploymentService = deploymentService;
        this.deploymentLogService = deploymentLogService;
        this.runtimeAdapters = runtimeAdapters;
        this.repositoryBuildServices = repositoryBuildServices;
        this.artifactRuntimeAdapters = artifactRuntimeAdapters;
        this.sampleModeEnabled = sampleModeEnabled;
    }

    public boolean processQueuedDeployment(UUID deploymentId) {
        if (!executionService.claimQueued(deploymentId)) return false;

        appendLog(deploymentId, DeploymentLogStage.QUEUE,
                DeploymentLogSeverity.INFO, "Worker claimed the queued deployment.");

        RepositoryBuildService repositoryBuild = repositoryBuildServices.getIfAvailable();
        if (repositoryBuild != null) {
            return processRepositoryBuild(deploymentId, repositoryBuild);
        }

        RuntimeAdapter runtime = runtimeAdapters.getIfAvailable();
        if (runtime == null) {
            appendLog(deploymentId, DeploymentLogStage.BUILD,
                    DeploymentLogSeverity.WARN,
                    "Execution is disabled because no runtime adapter is configured.");
            executionService.markFailed(deploymentId, RUNTIME_UNAVAILABLE_REASON);
            appendLog(deploymentId, DeploymentLogStage.WORKER,
                    DeploymentLogSeverity.ERROR,
                    "Deployment marked FAILED: runtime adapter unavailable.");
            return true;
        }

        if (!sampleModeEnabled) {
            String reason = "Repository build pipeline is disabled. No repository checkout or build was performed; "
                    + "the prebuilt sample runtime requires foundry.runtime.local.sample-mode-enabled=true.";
            executionService.markFailed(deploymentId, reason);
            appendLog(deploymentId, DeploymentLogStage.WORKER,
                    DeploymentLogSeverity.ERROR, reason);
            return true;
        }

        try {
            appendLog(deploymentId, DeploymentLogStage.BUILD,
                    DeploymentLogSeverity.INFO,
                    "Using the prebuilt trusted sample application; no repository checkout is performed.");

            executionService.advanceTo(deploymentId, DeploymentStatus.STARTING);
            URI applicationUrl = runtime.start(deploymentId);
            appendLog(deploymentId, DeploymentLogStage.STARTUP,
                    DeploymentLogSeverity.INFO, "Started the sample application at " + applicationUrl);

            executionService.advanceTo(deploymentId, DeploymentStatus.HEALTH_CHECK);
            appendLog(deploymentId, DeploymentLogStage.HEALTH_CHECK,
                    DeploymentLogSeverity.INFO, "Checking the application's /health endpoint.");

            if (!runtime.awaitHealthy(deploymentId, HEALTH_TIMEOUT)) {
                return failRuntime(deploymentId, runtime, HEALTH_FAILURE_REASON);
            }

            appendLog(deploymentId, DeploymentLogStage.HEALTH_CHECK,
                    DeploymentLogSeverity.INFO, "Health check passed: HTTP 200 and status UP.");
            executionService.markSucceeded(deploymentId, applicationUrl.toString());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failRuntime(deploymentId, runtime, "Deployment processing was interrupted.");
        } catch (Exception failure) {
            return failRuntime(
                    deploymentId, runtime,
                    RUNTIME_FAILURE_REASON + " Cause: " + describeFailure(failure));
        }
    }

    private boolean processRepositoryBuild(
            UUID deploymentId,
            RepositoryBuildService buildService) {
        RepositoryArtifactRuntime runtime = artifactRuntimeAdapters.getIfAvailable();
        if (runtime == null) {
            String reason = "Repository builds are enabled, but the local artifact runtime is disabled.";
            executionService.markFailed(deploymentId, reason);
            appendLog(deploymentId, DeploymentLogStage.WORKER,
                    DeploymentLogSeverity.ERROR, reason);
            return true;
        }

        try {
            DeploymentResponse deployment = deploymentService.findById(deploymentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Deployment disappeared before repository checkout: " + deploymentId));

            BuiltArtifact artifact = buildService.build(
                    deploymentId,
                    deployment.projectId(),
                    deployment.sourceRepositoryUrl(),
                    deployment.sourceRef(),
                    deployment.servicePath());

            appendLog(deploymentId, DeploymentLogStage.BUILD,
                    DeploymentLogSeverity.INFO,
                    "Repository build succeeded. Starting the built service.");

            executionService.advanceTo(deploymentId, DeploymentStatus.STARTING);
            URI applicationUrl = runtime.startArtifact(deploymentId, artifact);
            appendLog(deploymentId, DeploymentLogStage.STARTUP,
                    DeploymentLogSeverity.INFO, "Started the built service at " + applicationUrl);

            executionService.advanceTo(deploymentId, DeploymentStatus.HEALTH_CHECK);
            appendLog(deploymentId, DeploymentLogStage.HEALTH_CHECK,
                    DeploymentLogSeverity.INFO,
                    "Checking the built service at " + artifact.healthPath() + ".");

            if (!runtime.awaitArtifactHealthy(deploymentId, HEALTH_TIMEOUT, artifact)) {
                return failRuntime(deploymentId, runtime, HEALTH_FAILURE_REASON);
            }

            appendLog(deploymentId, DeploymentLogStage.HEALTH_CHECK,
                    DeploymentLogSeverity.INFO,
                    "Built service health check passed.");
            executionService.markSucceeded(deploymentId, applicationUrl.toString());
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failRuntime(deploymentId, runtime, "Repository build/deployment was interrupted.");
        } catch (Exception failure) {
            return failRuntime(
                    deploymentId, runtime,
                    "Repository build/deployment failed: " + describeFailure(failure));
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
                finalFailureReason += ": " + cleanupFailure.getMessage();
            }
        }

        executionService.markFailed(deploymentId, finalFailureReason);
        appendLog(deploymentId, DeploymentLogStage.WORKER,
                DeploymentLogSeverity.ERROR, "Deployment failed: " + finalFailureReason);
        return true;
    }

    private static String describeFailure(Exception failure) {
        String detail = failure.getClass().getSimpleName();
        if (failure.getMessage() != null && !failure.getMessage().isBlank()) {
            detail += ": " + failure.getMessage();
        }
        return detail;
    }

    private void appendLog(
            UUID deploymentId,
            DeploymentLogStage stage,
            DeploymentLogSeverity severity,
            String message) {
        deploymentLogService.append(
                deploymentId, stage, DeploymentLogStream.DEPLOYMENT, severity, message)
                .orElseThrow(() -> new IllegalStateException(
                        "Deployment disappeared while processing it: " + deploymentId));
    }
}
