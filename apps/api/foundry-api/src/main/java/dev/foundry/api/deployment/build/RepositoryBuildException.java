package dev.foundry.api.deployment.build;

public class RepositoryBuildException extends RuntimeException {
    public RepositoryBuildException(String message) {
        super(message);
    }

    public RepositoryBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
