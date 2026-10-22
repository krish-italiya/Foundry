package dev.foundry.api.deployment.build;

public record RepositoryBuildStatusResponse(
        boolean enabled,
        boolean trustedRepositoriesConfigured
) {
}
