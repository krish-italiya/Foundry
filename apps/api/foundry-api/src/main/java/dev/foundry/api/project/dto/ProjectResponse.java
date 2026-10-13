package dev.foundry.api.project.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String repositoryUrl,
        Instant createdAt
) {
}