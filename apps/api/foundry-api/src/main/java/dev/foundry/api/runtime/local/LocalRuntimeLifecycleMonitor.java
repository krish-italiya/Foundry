package dev.foundry.api.runtime.local;

import dev.foundry.api.deployment.DeploymentEntity;
import dev.foundry.api.deployment.DeploymentExecutionService;
import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.DeploymentStatus;
import dev.foundry.api.deployment.RuntimeState;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.foundry.api.runtime.AnyRuntimeEnabledCondition;
import dev.foundry.api.runtime.ManagedRepositoryArtifactRuntime;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciles persisted "healthy after initial rollout" records with the local
 * process registry. It detects unavailable processes; it does not restart them.
 */
@Component
@Conditional(AnyRuntimeEnabledCondition.class)
public class LocalRuntimeLifecycleMonitor {

    private static final Logger log = LoggerFactory.getLogger(LocalRuntimeLifecycleMonitor.class);

    private final DeploymentRepository deploymentRepository;
    private final DeploymentExecutionService executionService;
    private final ManagedRepositoryArtifactRuntime runtime;
    private final DeploymentLogService deploymentLogService;

    public LocalRuntimeLifecycleMonitor(
            DeploymentRepository deploymentRepository,
            DeploymentExecutionService executionService,
            ManagedRepositoryArtifactRuntime runtime,
            DeploymentLogService deploymentLogService) {
        this.deploymentRepository = deploymentRepository;
        this.executionService = executionService;
        this.runtime = runtime;
        this.deploymentLogService = deploymentLogService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileAfterStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${foundry.runtime.local.monitor-interval-ms:5000}")
    public void reconcilePeriodically() {
        reconcile();
    }

    /**
     * Public for focused tests; repeated calls are idempotent because the entity
     * transitions only from RUNNING and each unavailable state is persisted once.
     */
    public void reconcile() {
        List<DeploymentEntity> succeeded = deploymentRepository
                .findAllByStatusOrderByCreatedAtAsc(DeploymentStatus.SUCCEEDED);
        for (DeploymentEntity deployment : succeeded) {
            reconcileOne(deployment.getId());
        }
    }

    private void reconcileOne(UUID deploymentId) {
        Optional<Boolean> processAlive = runtime.processAlive(deploymentId);
        if (processAlive.isPresent() && processAlive.get()) {
            return;
        }

        RuntimeState unavailableState = processAlive.isEmpty()
                ? RuntimeState.INTERRUPTED
                : RuntimeState.CRASHED;
        String message = unavailableState == RuntimeState.INTERRUPTED
                ? "Deployment runtime is not tracked by the current Foundry process; marked INTERRUPTED."
                : "Application process exited unexpectedly; marked CRASHED.";

        try {
            if (!executionService.markRuntimeState(deploymentId, unavailableState)) {
                return;
            }

            try {
                runtime.stop(deploymentId);
            } catch (RuntimeException cleanupFailure) {
                log.warn("Runtime cleanup failed for deployment {}", deploymentId, cleanupFailure);
                message += " Runtime cleanup failed: " + cleanupFailure.getClass().getSimpleName();
            }

            deploymentLogService.append(
                    deploymentId,
                    DeploymentLogStage.RUNTIME,
                    DeploymentLogStream.DEPLOYMENT,
                    unavailableState == RuntimeState.CRASHED
                            ? DeploymentLogSeverity.ERROR
                            : DeploymentLogSeverity.WARN,
                    message);
        } catch (RuntimeException reconciliationFailure) {
            // A single stale row must not prevent monitoring the remaining deployments.
            log.warn("Could not reconcile runtime state for deployment {}", deploymentId,
                    reconciliationFailure);
        }
    }
}
