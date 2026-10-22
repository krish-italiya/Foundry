package dev.foundry.api.deployment.build;

public record BuildServiceDescriptor(
        String path,
        String name,
        String ecosystem,
        boolean hasTests,
        boolean hasBuild,
        String launchMode
) {
}
