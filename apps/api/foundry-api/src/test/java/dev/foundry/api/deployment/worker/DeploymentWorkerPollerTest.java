package dev.foundry.api.deployment.worker;

import dev.foundry.api.deployment.DeploymentEntity;
import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.DeploymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class DeploymentWorkerPollerTest {

    private final DeploymentRepository repository = mock(DeploymentRepository.class);
    private final DeploymentWorker worker = mock(DeploymentWorker.class);
    private final DeploymentWorkerPoller poller = new DeploymentWorkerPoller(repository, worker);

    @Test
    void doesNotClaimQueuedWorkUntilStartupRecoveryCompletes() {
        poller.poll();

        verifyNoInteractions(repository, worker);

        UUID deploymentId = UUID.randomUUID();
        DeploymentEntity queued = mock(DeploymentEntity.class);
        when(queued.getId()).thenReturn(deploymentId);
        when(repository.findFirstByStatusOrderByCreatedAtAsc(DeploymentStatus.QUEUED))
                .thenReturn(Optional.of(queued));
        when(worker.processQueuedDeployment(deploymentId)).thenReturn(true);

        poller.enableAfterRuntimeRecovery(mock(ApplicationReadyEvent.class));
        poller.poll();

        verify(repository).findFirstByStatusOrderByCreatedAtAsc(DeploymentStatus.QUEUED);
        verify(worker).processQueuedDeployment(deploymentId);
    }
}