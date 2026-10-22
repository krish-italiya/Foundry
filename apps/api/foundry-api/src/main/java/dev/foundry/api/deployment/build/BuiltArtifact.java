package dev.foundry.api.deployment.build;

import java.nio.file.Path;

/**
 * Describes the runnable artifact produced by a supported repository build.
 *
 * @param workspaceDirectory temporary checkout root owned by this deployment
 * @param serviceDirectory directory containing the selected service
 * @param artifactPath built artifact path; null when the runtime executes an npm script
 * @param launchMode runtime strategy for the artifact
 * @param healthPath HTTP path used by the runtime readiness probe
 * @param expectedHealthMarker optional response-body text that must be present
 */
public record BuiltArtifact(
        Path workspaceDirectory,
        Path serviceDirectory,
        Path artifactPath,
        LaunchMode launchMode,
        String healthPath,
        String expectedHealthMarker
) {
    public enum LaunchMode {
        SPRING_BOOT_JAR,
        VITE_PREVIEW,
        NODE_START
    }
}
