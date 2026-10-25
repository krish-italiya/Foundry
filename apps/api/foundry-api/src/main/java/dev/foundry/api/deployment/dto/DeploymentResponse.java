package dev.foundry.api.deployment.dto;

import dev.foundry.api.deployment.DeploymentStatus;
import dev.foundry.api.deployment.RuntimeState;

import java.time.Instant;
import java.util.UUID;

public record DeploymentResponse(
        UUID id,
        UUID projectId,
        String sourceRepositoryUrl,
        String sourceRef,
        DeploymentStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String failureReason,
        String applicationUrl,
        String servicePath,
        RuntimeState runtimeState
) {
}