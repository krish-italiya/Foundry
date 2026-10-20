package dev.foundry.api.deployment.worker;

import dev.foundry.api.deployment.DeploymentEntity;
import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.DeploymentStatus;
import dev.foundry.api.deployment.dto.DeploymentLogsResponse;
import dev.foundry.api.deployment.log.DeploymentLogRepository;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.project.ProjectEntity;
import dev.foundry.api.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "foundry.worker.enabled=false",
        "foundry.runtime.local.enabled=false"
})
class DeploymentWorkerIntegrationTest {

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

    @BeforeEach
    void cleanDatabase() {
        deploymentLogRepository.deleteAll();
        deploymentRepository.deleteAll();
        projectRepository.deleteAll();
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
    void workerFailsSafelyAndRecordsLogsWhenRuntimeIsUnavailable() {
        DeploymentEntity deployment = createQueuedDeployment();

        boolean processed = deploymentWorker.processQueuedDeployment(
                deployment.getId()
        );

        assertThat(processed).isTrue();

        DeploymentEntity updated = deploymentRepository
                .findById(deployment.getId())
                .orElseThrow();

        assertThat(updated.getStatus())
                .isEqualTo(DeploymentStatus.FAILED);
        assertThat(updated.getStartedAt()).isNotNull();
        assertThat(updated.getCompletedAt()).isNotNull();
        assertThat(updated.getFailureReason())
                .contains("no runtime adapter");

        DeploymentLogsResponse logs = deploymentLogService.findLogs(
                deployment.getId(), 0, 10
        ).orElseThrow();

        assertThat(logs.entries()).hasSize(3);
        assertThat(logs.entries())
                .extracting(entry -> entry.sequenceNumber())
                .containsExactly(1L, 2L, 3L);
        assertThat(logs.entries().get(0).message())
                .contains("Worker claimed");
        assertThat(logs.entries().get(1).message())
                .contains("no runtime adapter");
        assertThat(logs.entries().get(2).severity())
                .isEqualTo(
                        dev.foundry.api.deployment.log.DeploymentLogSeverity.ERROR
                );
        assertThat(logs.hasMore()).isFalse();
    }

    @Test
    void unknownDeploymentIsNotProcessed() {
        assertThat(
                deploymentWorker.processQueuedDeployment(UUID.randomUUID())
        ).isFalse();

        assertThat(deploymentLogRepository.count()).isZero();
    }

    @Test
    void alreadyFailedDeploymentIsNotProcessedAgain() {
        DeploymentEntity deployment = createQueuedDeployment();

        deployment.transitionTo(DeploymentStatus.BUILDING);
        deployment.fail("Previously failed");
        deploymentRepository.save(deployment);

        assertThat(
                deploymentWorker.processQueuedDeployment(deployment.getId())
        ).isFalse();

        DeploymentEntity unchanged = deploymentRepository
                .findById(deployment.getId())
                .orElseThrow();

        assertThat(unchanged.getStatus())
                .isEqualTo(DeploymentStatus.FAILED);
        assertThat(deploymentLogRepository.count()).isZero();
    }
}