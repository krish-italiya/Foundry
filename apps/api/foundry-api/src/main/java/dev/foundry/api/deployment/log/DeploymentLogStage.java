package dev.foundry.api.deployment.log;

public enum DeploymentLogStage {
    QUEUE,
    SOURCE_CHECKOUT,
    BUILD,
    IMAGE,
    STARTUP,
    HEALTH_CHECK,
    RUNTIME,
    WORKER
}