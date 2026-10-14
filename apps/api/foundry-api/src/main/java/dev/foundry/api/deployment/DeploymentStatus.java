package dev.foundry.api.deployment;

public enum DeploymentStatus {
    QUEUED,
    BUILDING,
    STARTING,
    HEALTH_CHECK,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}