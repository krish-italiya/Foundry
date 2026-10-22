package dev.foundry.api.deployment.build;

import java.nio.file.Path;

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
        PORT_ARGUMENT_JAR,
        VITE_PREVIEW
    }
}
