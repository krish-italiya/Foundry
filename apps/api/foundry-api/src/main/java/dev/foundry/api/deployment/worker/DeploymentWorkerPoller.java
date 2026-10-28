package dev.foundry.api.deployment.worker;

import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.DeploymentStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
    private volatile boolean startupRecoveryComplete;

    public DeploymentWorkerPoller(
            DeploymentRepository deploymentRepository,
            DeploymentWorker deploymentWorker) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentWorker = deploymentWorker;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void enableAfterRuntimeRecovery(ApplicationReadyEvent event) {
        // Runtime cleanup and stranded-deployment recovery have higher event priority.
        startupRecoveryComplete = true;
    }

    @Scheduled(
            fixedDelayString = "${foundry.worker.poll-interval-ms:1000}"
    )
    public void poll() {
        if (!startupRecoveryComplete) return;
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