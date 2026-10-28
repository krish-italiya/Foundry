package dev.foundry.api.runtime;

import dev.foundry.api.deployment.DeploymentEntity;
import dev.foundry.api.deployment.DeploymentExecutionService;
import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.DeploymentStatus;
import dev.foundry.api.deployment.RuntimeState;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class RuntimeLifecycleMonitorTest {

    private final DeploymentRepository repository = mock(DeploymentRepository.class);
    private final DeploymentExecutionService executionService = mock(DeploymentExecutionService.class);
    private final ManagedRepositoryArtifactRuntime runtime = mock(ManagedRepositoryArtifactRuntime.class);
    private final DeploymentLogService logService = mock(DeploymentLogService.class);

    private final LocalRuntimeLifecycleMonitor monitor = new RuntimeLifecycleMonitor(
            repository, executionService, runtime, logService);

    private final UUID deploymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        DeploymentEntity deployment = mock(DeploymentEntity.class);
        when(deployment.getId()).thenReturn(deploymentId);
        when(repository.findAllByStatusOrderByCreatedAtAsc(DeploymentStatus.SUCCEEDED))
                .thenReturn(List.of(deployment));
        when(repository.findAllByStatusOrderByCreatedAtAsc(DeploymentStatus.BUILDING))
                .thenReturn(List.of());
        when(repository.findAllByStatusOrderByCreatedAtAsc(DeploymentStatus.STARTING))
                .thenReturn(List.of());
        when(repository.findAllByStatusOrderByCreatedAtAsc(DeploymentStatus.HEALTH_CHECK))
                .thenReturn(List.of());
    }

    @Test
    void startupRecoveryFailsStrandedBuildAndCleansItsRuntimeBeforeWorkerResumes() {
        UUID strandedId = UUID.randomUUID();
        DeploymentEntity stranded = mock(DeploymentEntity.class);
        when(stranded.getId()).thenReturn(strandedId);
        when(repository.findAllByStatusOrderByCreatedAtAsc(DeploymentStatus.BUILDING))
                .thenReturn(List.of(stranded));
        when(executionService.failIfInProgress(
                eq(strandedId), contains("Foundry restarted before deployment completed")))
                .thenReturn(true);
        when(runtime.processAlive(deploymentId)).thenReturn(Optional.of(true));

        monitor.reconcileAfterStartup();

        verify(executionService).failIfInProgress(
                eq(strandedId), contains("Foundry restarted before deployment completed"));
        verify(runtime).stop(strandedId);
        verify(runtime, never()).stop(deploymentId);
        verify(logService).append(
                strandedId,
                DeploymentLogStage.WORKER,
                DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.WARN,
                "Foundry restarted while deployment was BUILDING; marked FAILED and cleaned up its runtime.");
    }

    @Test
    void marksExitedProcessCrashedAndRemovesItsRoute() {
        when(runtime.processAlive(deploymentId)).thenReturn(Optional.of(false));
        when(executionService.markRuntimeState(deploymentId, RuntimeState.CRASHED))
                .thenReturn(true);

        monitor.reconcile();

        verify(executionService).markRuntimeState(deploymentId, RuntimeState.CRASHED);
        verify(runtime).stop(deploymentId);
        verify(logService).append(
                deploymentId,
                DeploymentLogStage.RUNTIME,
                DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.ERROR,
                "Application process exited unexpectedly; marked CRASHED.");
    }

    @Test
    void marksUntrackedProcessInterruptedAfterControlPlaneRestart() {
        when(runtime.processAlive(deploymentId)).thenReturn(Optional.empty());
        when(executionService.markRuntimeState(deploymentId, RuntimeState.INTERRUPTED))
                .thenReturn(true);

        monitor.reconcile();

        verify(executionService).markRuntimeState(deploymentId, RuntimeState.INTERRUPTED);
        verify(runtime).stop(deploymentId);
        verify(logService).append(
                deploymentId,
                DeploymentLogStage.RUNTIME,
                DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.WARN,
                "Deployment runtime is not tracked by the current Foundry process; marked INTERRUPTED.");
    }

    @Test
    void leavesRunningProcessAlone() {
        when(runtime.processAlive(deploymentId)).thenReturn(Optional.of(true));

        monitor.reconcile();

        verify(executionService, never()).markRuntimeState(any(), any());
        verify(runtime, never()).stop(any());
        verifyNoInteractions(logService);
    }

    @Test
    void repeatedReconciliationDoesNotCleanUpAgainAfterStateAlreadyChanged() {
        when(runtime.processAlive(deploymentId)).thenReturn(Optional.of(false));
        when(executionService.markRuntimeState(deploymentId, RuntimeState.CRASHED))
                .thenReturn(false);

        monitor.reconcile();

        verify(executionService).markRuntimeState(deploymentId, RuntimeState.CRASHED);
        verify(runtime, never()).stop(any());
        verifyNoInteractions(logService);
    }
}
