package dev.foundry.api.deployment;

/**
 * Runtime health is separate from deployment creation/build status.
 * SUCCEEDED means the application passed its initial health check;
 * runtime state continues to describe whether that process is available.
 */
public enum RuntimeState {
    NOT_STARTED,
    RUNNING,
    STOPPED,
    CRASHED,
    INTERRUPTED
}
