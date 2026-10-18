package dev.foundry.api.deployment.dto;

import java.util.List;
import java.util.UUID;

public record DeploymentLogsResponse(
        UUID deploymentId,
        List<DeploymentLogEntryResponse> entries,
        long nextAfterSequence,
        boolean hasMore
) {
}