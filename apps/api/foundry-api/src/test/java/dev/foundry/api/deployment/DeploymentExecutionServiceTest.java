package dev.foundry.api.deployment;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeploymentExecutionServiceTest {

    private final DeploymentRepository repository = mock(DeploymentRepository.class);
    private final DeploymentExecutionService service = new DeploymentExecutionService(repository);
    private final UUID deploymentId = UUID.randomUUID();

    @Test
    void failIfInProgressMarksBuildPhaseAsFailed() {
        DeploymentEntity deployment = mock(DeploymentEntity.class);
        when(repository.findByIdForUpdate(deploymentId)).thenReturn(Optional.of(deployment));
        when(deployment.getStatus()).thenReturn(DeploymentStatus.BUILDING);
        String reason = "Foundry restarted before deployment completed.";

        boolean changed = service.failIfInProgress(deploymentId, reason);

        assertThat(changed).isTrue();
        verify(deployment).fail(reason);
    }

    @Test
    void failIfInProgressMarksHealthCheckInterruptedAsFailed() {
        DeploymentEntity deployment = mock(DeploymentEntity.class);
        when(repository.findByIdForUpdate(deploymentId)).thenReturn(Optional.of(deployment));
        when(deployment.getStatus()).thenReturn(DeploymentStatus.HEALTH_CHECK);
        String reason = "Foundry restarted before deployment completed.";

        boolean changed = service.failIfInProgress(deploymentId, reason);

        assertThat(changed).isTrue();
        verify(deployment).fail(reason);
    }

    @Test
    void failIfInProgressDoesNotModifyQueuedDeployment() {
        DeploymentEntity deployment = mock(DeploymentEntity.class);
        when(repository.findByIdForUpdate(deploymentId)).thenReturn(Optional.of(deployment));
        when(deployment.getStatus()).thenReturn(DeploymentStatus.QUEUED);

        boolean changed = service.failIfInProgress(deploymentId, "restart");

        assertThat(changed).isFalse();
        verify(deployment, never()).fail(anyString());
    }

    @Test
    void failIfInProgressDoesNotModifyTerminalDeployment() {
        DeploymentEntity deployment = mock(DeploymentEntity.class);
        when(repository.findByIdForUpdate(deploymentId)).thenReturn(Optional.of(deployment));
        when(deployment.getStatus()).thenReturn(DeploymentStatus.SUCCEEDED);

        boolean changed = service.failIfInProgress(deploymentId, "restart");

        assertThat(changed).isFalse();
        verify(deployment, never()).fail(anyString());
    }
}