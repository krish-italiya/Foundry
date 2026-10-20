package dev.foundry.api.runtime;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

public interface RuntimeAdapter {

    URI start(UUID deploymentId) throws IOException;

    boolean awaitHealthy(
            UUID deploymentId,
            Duration timeout) throws InterruptedException;

    void stop(UUID deploymentId);
}