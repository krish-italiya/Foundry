package dev.foundry.api.deployment;

import dev.foundry.api.project.ProjectEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentEntityTest {

    private DeploymentEntity newDeployment() {
        ProjectEntity project = new ProjectEntity(
                "hello-world",
                "https://github.com/example/hello-world"
        );

        return new DeploymentEntity(project, "main");
    }

    @Test
    void newDeploymentStartsQueuedAndCapturesSource() {
        DeploymentEntity deployment = newDeployment();

        assertEquals(DeploymentStatus.QUEUED, deployment.getStatus());
        assertEquals(RuntimeState.NOT_STARTED, deployment.getRuntimeState());
        assertNotNull(deployment.getCreatedAt());
        assertEquals(
                "https://github.com/example/hello-world",
                deployment.getSourceRepositoryUrl()
        );
        assertEquals("main", deployment.getSourceRef());
        assertNull(deployment.getStartedAt());
        assertNull(deployment.getCompletedAt());
    }

    @Test
    void successfulLifecycleFollowsAllowedTransitions() {
        DeploymentEntity deployment = newDeployment();

        deployment.transitionTo(DeploymentStatus.BUILDING);
        assertNotNull(deployment.getStartedAt());

        deployment.transitionTo(DeploymentStatus.STARTING);
        deployment.transitionTo(DeploymentStatus.HEALTH_CHECK);
        deployment.markHealthCheckPassed();

        assertEquals(DeploymentStatus.SUCCEEDED, deployment.getStatus());
        assertEquals(RuntimeState.RUNNING, deployment.getRuntimeState());
        assertNotNull(deployment.getCompletedAt());
        assertNull(deployment.getFailureReason());
        assertTrue(deployment.getStatus().isTerminal());
    }

    @Test
    void runtimeCanTransitionFromRunningToCrashedOnlyOnce() {
        DeploymentEntity deployment = newDeployment();
        deployment.transitionTo(DeploymentStatus.BUILDING);
        deployment.transitionTo(DeploymentStatus.STARTING);
        deployment.transitionTo(DeploymentStatus.HEALTH_CHECK);
        deployment.markHealthCheckPassed();

        assertTrue(deployment.markRuntimeState(RuntimeState.CRASHED));
        assertEquals(RuntimeState.CRASHED, deployment.getRuntimeState());
        assertFalse(deployment.markRuntimeState(RuntimeState.INTERRUPTED));
    }

    @Test
    void runtimeCannotBeMarkedUnavailableBeforeDeploymentSucceeds() {
        DeploymentEntity deployment = newDeployment();

        assertFalse(deployment.markRuntimeState(RuntimeState.CRASHED));
        assertEquals(RuntimeState.NOT_STARTED, deployment.getRuntimeState());
    }

    @Test
    void cannotMarkRuntimeAsNotStartedDuringReconciliation() {
        DeploymentEntity deployment = newDeployment();

        assertThrows(IllegalArgumentException.class,
                () -> deployment.markRuntimeState(RuntimeState.NOT_STARTED));
    }

    @Test
    void cannotSkipRequiredStages() {
        DeploymentEntity deployment = newDeployment();

        assertThrows(
                IllegalStateException.class,
                () -> deployment.transitionTo(DeploymentStatus.STARTING)
        );

        assertEquals(DeploymentStatus.QUEUED, deployment.getStatus());
    }

    @Test
    void cannotSucceedBeforeHealthCheck() {
        DeploymentEntity deployment = newDeployment();

        assertThrows(
                IllegalStateException.class,
                deployment::markHealthCheckPassed
        );

        assertEquals(DeploymentStatus.QUEUED, deployment.getStatus());
    }

    @Test
    void failureRecordsReasonAndCompletionTime() {
        DeploymentEntity deployment = newDeployment();

        deployment.fail("Image build failed");

        assertEquals(DeploymentStatus.FAILED, deployment.getStatus());
        assertEquals("Image build failed", deployment.getFailureReason());
        assertNotNull(deployment.getCompletedAt());
        assertNull(deployment.getStartedAt());
    }

    @Test
    void cannotTransitionAfterFailure() {
        DeploymentEntity deployment = newDeployment();
        deployment.fail("Repository unavailable");

        assertThrows(
                IllegalStateException.class,
                () -> deployment.transitionTo(DeploymentStatus.BUILDING)
        );
    }

    @Test
    void cannotFailWithoutAReason() {
        DeploymentEntity deployment = newDeployment();

        assertThrows(
                IllegalArgumentException.class,
                () -> deployment.fail("   ")
        );

        assertEquals(DeploymentStatus.QUEUED, deployment.getStatus());
    }
}