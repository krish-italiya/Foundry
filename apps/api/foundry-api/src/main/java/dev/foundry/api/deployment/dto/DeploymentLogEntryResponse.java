package dev.foundry.api.deployment.dto;

import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;

import java.time.Instant;
import java.util.UUID;

public record DeploymentLogEntryResponse(
        UUID id,
        UUID deploymentId,
        long sequenceNumber,
        Instant timestamp,
        DeploymentLogStage stage,
        DeploymentLogStream stream,
        DeploymentLogSeverity severity,
        String message
) {
}