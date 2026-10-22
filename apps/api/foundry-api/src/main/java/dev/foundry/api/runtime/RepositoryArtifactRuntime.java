package dev.foundry.api.runtime;

import dev.foundry.api.deployment.build.BuiltArtifact;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/** Runtime adapter capability for executing an artifact produced from a repository build. */
public interface RepositoryArtifactRuntime extends RuntimeAdapter {
    URI startArtifact(UUID deploymentId, BuiltArtifact artifact) throws IOException;

    boolean awaitArtifactHealthy(
            UUID deploymentId,
            Duration timeout,
            BuiltArtifact artifact) throws InterruptedException;
}
