package dev.foundry.api.runtime;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository-capable runtime with the lookup hooks needed by local development
 * ingress and lifecycle reconciliation. Implementations must keep their
 * published endpoints bound to loopback unless a separate ingress layer is introduced.
 */
public interface ManagedRepositoryArtifactRuntime extends RepositoryArtifactRuntime {

    /** Returns the private loopback endpoint only while this runtime tracks the deployment. */
    Optional<URI> internalApplicationUrl(UUID deploymentId);

    /**
     * @return empty when the runtime is not tracked by this control-plane process; otherwise
     * whether its process/container is alive. A Docker liveness query that cannot be confirmed
     * is treated as alive until a cleanup retry resolves the uncertainty.
     */
    Optional<Boolean> processAlive(UUID deploymentId);
}
