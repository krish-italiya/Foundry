package dev.foundry.api.project.dto;

import jakarta.validation.constraints.NotNull;

public record ProjectBuildTrustRequest(
        @NotNull(message = "Build trust decision is required")
        Boolean buildTrusted
) {
}
