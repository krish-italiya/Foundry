package dev.foundry.api.deployment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class DeploymentExecutionService {

    private final DeploymentRepository deploymentRepository;

    public DeploymentExecutionService(
            DeploymentRepository deploymentRepository) {
        this.deploymentRepository = deploymentRepository;
    }

    @Transactional
    public boolean claimQueued(UUID deploymentId) {
        Optional<DeploymentEntity> result =
                deploymentRepository.findByIdForUpdate(deploymentId);

        if (result.isEmpty()) {
            return false;
        }

        DeploymentEntity deployment = result.get();

        if (deployment.getStatus() != DeploymentStatus.QUEUED) {
            return false;
        }

        deployment.transitionTo(DeploymentStatus.BUILDING);
        return true;
    }

    @Transactional
    public void advanceTo(
            UUID deploymentId,
            DeploymentStatus nextStatus) {
        lockedDeployment(deploymentId).transitionTo(nextStatus);
    }

    @Transactional
    public void markSucceeded(
            UUID deploymentId,
            String applicationUrl) {
        lockedDeployment(deploymentId)
                .markHealthCheckPassed(applicationUrl);
    }

    @Transactional
    public boolean markRuntimeState(UUID deploymentId, RuntimeState runtimeState) {
        return lockedDeployment(deploymentId).markRuntimeState(runtimeState);
    }

    @Transactional
    public void markFailed(
            UUID deploymentId,
            String reason) {
        DeploymentEntity deployment = lockedDeployment(deploymentId);

        if (!deployment.getStatus().isTerminal()) {
            deployment.fail(reason);
        }
    }

    @Transactional
    public boolean failIfInProgress(UUID deploymentId, String reason) {
        DeploymentEntity deployment = lockedDeployment(deploymentId);
        DeploymentStatus status = deployment.getStatus();
        if (status != DeploymentStatus.BUILDING
                && status != DeploymentStatus.STARTING
                && status != DeploymentStatus.HEALTH_CHECK) {
            return false;
        }
        deployment.fail(reason);
        return true;
    }

    private DeploymentEntity lockedDeployment(UUID deploymentId) {
        return deploymentRepository.findByIdForUpdate(deploymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Deployment not found: " + deploymentId));
    }
}