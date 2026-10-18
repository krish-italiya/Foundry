package dev.foundry.api.deployment.worker;

import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.DeploymentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "foundry.worker",
        name = "enabled",
        havingValue = "true"
)
public class DeploymentWorkerPoller {

    private final DeploymentRepository deploymentRepository;
    private final DeploymentWorker deploymentWorker;

    public DeploymentWorkerPoller(
            DeploymentRepository deploymentRepository,
            DeploymentWorker deploymentWorker) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentWorker = deploymentWorker;
    }

    @Scheduled(
            fixedDelayString = "${foundry.worker.poll-interval-ms:1000}"
    )
    public void poll() {
        deploymentRepository
                .findFirstByStatusOrderByCreatedAtAsc(
                        DeploymentStatus.QUEUED
                )
                .ifPresent(deployment ->
                        deploymentWorker.processQueuedDeployment(
                                deployment.getId()
                        )
                );
    }
}