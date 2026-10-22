package dev.foundry.api.deployment.build;

/**
 * Signals a repository checkout, detection, or build-plan failure.
 */
public class RepositoryBuildException extends RuntimeException {
    public RepositoryBuildException(String message) {
        super(message);
    }

    public RepositoryBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
