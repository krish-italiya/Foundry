package dev.foundry.api.deployment.worker;

import dev.foundry.api.deployment.DeploymentEntity;
import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.DeploymentStatus;
import dev.foundry.api.deployment.log.DeploymentLogRepository;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.project.ProjectEntity;
import dev.foundry.api.project.ProjectRepository;
import dev.foundry.api.runtime.RuntimeAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "foundry.worker.enabled=false",
        "foundry.runtime.local.enabled=false"
})
class DeploymentWorkerRuntimeIntegrationTest {

    @Autowired
    private DeploymentWorker deploymentWorker;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DeploymentRepository deploymentRepository;

    @Autowired
    private DeploymentLogRepository deploymentLogRepository;

    @Autowired
    private DeploymentLogService deploymentLogService;

    @MockitoBean
    private RuntimeAdapter runtimeAdapter;

    @BeforeEach
    void cleanDatabase() {
        deploymentLogRepository.deleteAll();
        deploymentRepository.deleteAll();
        projectRepository.deleteAll();
        reset(runtimeAdapter);
    }

    private DeploymentEntity createQueuedDeployment() {
        ProjectEntity project = projectRepository.save(
                new ProjectEntity(
                        "hello-world",
                        "https://github.com/example/hello-world"
                )
        );

        return deploymentRepository.save(
                new DeploymentEntity(project, "main")
        );
    }

    @Test
    void successfulHealthCheckPersistsUrlAndSuccessState()
            throws Exception {

        DeploymentEntity deployment = createQueuedDeployment();
        URI url = URI.create("http://127.0.0.1:43210");

        when(runtimeAdapter.start(deployment.getId())).thenReturn(url);
        when(runtimeAdapter.awaitHealthy(
                eq(deployment.getId()), any(Duration.class)))
                .thenReturn(true);

        assertThat(deploymentWorker.processQueuedDeployment(
                deployment.getId())).isTrue();

        DeploymentEntity completed = deploymentRepository
                .findById(deployment.getId())
                .orElseThrow();

        assertThat(completed.getStatus())
                .isEqualTo(DeploymentStatus.SUCCEEDED);
        assertThat(completed.getApplicationUrl())
                .isEqualTo(url.toString());
        assertThat(completed.getStartedAt()).isNotNull();
        assertThat(completed.getCompletedAt()).isNotNull();

        var logs = deploymentLogService.findLogs(
                deployment.getId(), 0, 10).orElseThrow();

        assertThat(logs.entries())
                .extracting(entry -> entry.sequenceNumber())
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(logs.entries().get(4).message())
                .contains("Health check passed");

        verify(runtimeAdapter).awaitHealthy(
                eq(deployment.getId()), any(Duration.class));
    }

    @Test
    void failedHealthCheckStopsRuntimeAndFailsDeployment()
            throws Exception {

        DeploymentEntity deployment = createQueuedDeployment();

        when(runtimeAdapter.start(deployment.getId()))
                .thenReturn(URI.create("http://127.0.0.1:43210"));
        when(runtimeAdapter.awaitHealthy(
                eq(deployment.getId()), any(Duration.class)))
                .thenReturn(false);

        assertThat(deploymentWorker.processQueuedDeployment(
                deployment.getId())).isTrue();

        DeploymentEntity failed = deploymentRepository
                .findById(deployment.getId())
                .orElseThrow();

        assertThat(failed.getStatus())
                .isEqualTo(DeploymentStatus.FAILED);
        assertThat(failed.getFailureReason())
                .contains("health check");
        assertThat(failed.getApplicationUrl()).isNull();

        verify(runtimeAdapter).stop(deployment.getId());
    }

    @Test
    void cleanupFailureDoesNotPreventDeploymentFromBeingMarkedFailed()
            throws Exception {

        DeploymentEntity deployment = createQueuedDeployment();

        when(runtimeAdapter.start(deployment.getId()))
                .thenReturn(URI.create("http://127.0.0.1:43210"));

        when(runtimeAdapter.awaitHealthy(
                eq(deployment.getId()), any(Duration.class)))
                .thenReturn(false);

        doThrow(new IllegalStateException("simulated cleanup failure"))
                .when(runtimeAdapter)
                .stop(deployment.getId());

        assertThat(deploymentWorker.processQueuedDeployment(
                deployment.getId())).isTrue();

        DeploymentEntity failed = deploymentRepository
                .findById(deployment.getId())
                .orElseThrow();

        assertThat(failed.getStatus())
                .isEqualTo(DeploymentStatus.FAILED);

        assertThat(failed.getFailureReason())
                .contains(
                        "health check",
                        "Runtime cleanup failed",
                        "simulated cleanup failure"
                );

        verify(runtimeAdapter).stop(deployment.getId());
    }
}
