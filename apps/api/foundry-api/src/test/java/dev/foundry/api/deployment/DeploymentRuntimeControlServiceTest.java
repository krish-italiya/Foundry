package dev.foundry.api.deployment;

import dev.foundry.api.deployment.dto.DeploymentResponse;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import dev.foundry.api.runtime.local.LocalProcessRuntimeAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentRuntimeControlServiceTest {

    private final DeploymentService deploymentService = mock(DeploymentService.class);
    private final DeploymentExecutionService executionService = mock(DeploymentExecutionService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LocalProcessRuntimeAdapter> runtimeAdapters = mock(ObjectProvider.class);
    private final LocalProcessRuntimeAdapter runtime = mock(LocalProcessRuntimeAdapter.class);
    private final DeploymentLogService logService = mock(DeploymentLogService.class);
    private final DeploymentRuntimeControlService service = new DeploymentRuntimeControlService(
            deploymentService, executionService, runtimeAdapters, logService);

    private final UUID deploymentId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    private DeploymentResponse deployment(RuntimeState runtimeState) {
        return new DeploymentResponse(
                deploymentId,
                projectId,
                "https://github.com/example/hello-world",
                "main",
                DeploymentStatus.SUCCEEDED,
                Instant.parse("2026-10-26T10:00:00Z"),
                Instant.parse("2026-10-26T10:01:00Z"),
                Instant.parse("2026-10-26T10:02:00Z"),
                null,
                "http://" + deploymentId + ".localhost:8080",
                null,
                runtimeState);
    }

    @Test
    void stopsRunningDeploymentRemovesRuntimeAndRecordsLog() {
        DeploymentResponse running = deployment(RuntimeState.RUNNING);
        DeploymentResponse stopped = deployment(RuntimeState.STOPPED);
        when(deploymentService.findById(deploymentId))
                .thenReturn(Optional.of(running), Optional.of(stopped));
        when(runtimeAdapters.getIfAvailable()).thenReturn(runtime);
        when(executionService.tryMarkRuntimeState(deploymentId, RuntimeState.STOPPED))
                .thenReturn(Optional.of(true));

        DeploymentRuntimeControlService.StopResult result = service.stop(deploymentId);

        assertEquals(DeploymentRuntimeControlService.StopOutcome.STOPPED, result.outcome());
        assertEquals(RuntimeState.STOPPED, result.deployment().runtimeState());
        verify(executionService).tryMarkRuntimeState(deploymentId, RuntimeState.STOPPED);
        verify(runtime).stop(deploymentId);
        verify(logService).append(
                deploymentId,
                DeploymentLogStage.RUNTIME,
                DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.INFO,
                "Application runtime stopped by user request.");
    }

    @Test
    void unknownDeploymentReturnsNotFoundWithoutStoppingAnything() {
        when(deploymentService.findById(deploymentId)).thenReturn(Optional.empty());

        DeploymentRuntimeControlService.StopResult result = service.stop(deploymentId);

        assertEquals(DeploymentRuntimeControlService.StopOutcome.NOT_FOUND, result.outcome());
        verifyNoInteractions(executionService, runtime, logService);
    }

    @Test
    void nonRunningDeploymentReturnsConflictOutcome() {
        when(deploymentService.findById(deploymentId))
                .thenReturn(Optional.of(deployment(RuntimeState.CRASHED)));

        DeploymentRuntimeControlService.StopResult result = service.stop(deploymentId);

        assertEquals(DeploymentRuntimeControlService.StopOutcome.NOT_RUNNING, result.outcome());
        verifyNoInteractions(executionService, runtime, logService);
    }

    @Test
    void missingLocalRuntimeDoesNotChangeState() {
        when(deploymentService.findById(deploymentId))
                .thenReturn(Optional.of(deployment(RuntimeState.RUNNING)));
        when(runtimeAdapters.getIfAvailable()).thenReturn(null);

        DeploymentRuntimeControlService.StopResult result = service.stop(deploymentId);

        assertEquals(DeploymentRuntimeControlService.StopOutcome.RUNTIME_UNAVAILABLE, result.outcome());
        verifyNoInteractions(executionService, runtime, logService);
    }

    @Test
    void concurrentStateChangePreventsDuplicateStop() {
        when(deploymentService.findById(deploymentId))
                .thenReturn(Optional.of(deployment(RuntimeState.RUNNING)));
        when(runtimeAdapters.getIfAvailable()).thenReturn(runtime);
        when(executionService.tryMarkRuntimeState(deploymentId, RuntimeState.STOPPED))
                .thenReturn(Optional.of(false));

        DeploymentRuntimeControlService.StopResult result = service.stop(deploymentId);

        assertEquals(DeploymentRuntimeControlService.StopOutcome.NOT_RUNNING, result.outcome());
        verify(runtime, never()).stop(any());
        verifyNoInteractions(logService);
    }
}
