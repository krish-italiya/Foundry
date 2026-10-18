package dev.foundry.api.deployment.worker;

import dev.foundry.api.deployment.DeploymentEntity;
import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.DeploymentStatus;
import dev.foundry.api.deployment.dto.DeploymentLogsResponse;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class DeploymentWorker {

    private static final String RUNTIME_UNAVAILABLE_REASON =
            "Deployment execution is disabled because no runtime adapter "
                    + "is configured.";

    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogService deploymentLogService;

    public DeploymentWorker(
            DeploymentRepository deploymentRepository,
            DeploymentLogService deploymentLogService) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentLogService = deploymentLogService;
    }

    @Transactional
    public boolean processQueuedDeployment(UUID deploymentId) {
        Optional<DeploymentEntity> result =
                deploymentRepository.findByIdForUpdate(deploymentId);

        if (result.isEmpty()) {
            return false;
        }

        DeploymentEntity deployment = result.get();

        // A queued job may already have been claimed by another worker.
        if (deployment.getStatus() != DeploymentStatus.QUEUED) {
            return false;
        }

        deployment.transitionTo(DeploymentStatus.BUILDING);

        appendLog(
                deploymentId,
                DeploymentLogStage.QUEUE,
                DeploymentLogSeverity.INFO,
                "Worker claimed the queued deployment."
        );

        appendLog(
                deploymentId,
                DeploymentLogStage.BUILD,
                DeploymentLogSeverity.WARN,
                "Execution is disabled because no runtime adapter is configured."
        );

        deployment.fail(RUNTIME_UNAVAILABLE_REASON);

        appendLog(
                deploymentId,
                DeploymentLogStage.WORKER,
                DeploymentLogSeverity.ERROR,
                "Deployment marked FAILED: runtime adapter unavailable."
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