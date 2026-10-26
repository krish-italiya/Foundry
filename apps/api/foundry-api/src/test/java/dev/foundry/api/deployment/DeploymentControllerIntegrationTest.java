package dev.foundry.api.deployment;

import dev.foundry.api.deployment.log.*;
import dev.foundry.api.project.ProjectEntity;
import dev.foundry.api.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DeploymentControllerIntegrationTest {

    private static final String API_URL = "/api/v1";

    @Autowired
    private MockMvc mockMvc;

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

    private ProjectEntity createProject() {
        return projectRepository.save(new ProjectEntity(
                "hello-world",
                "https://github.com/example/hello-world"
        ));
    }

    private String requestWithSourceRef(String sourceRef) {
        return """
                {
                  "sourceRef": "%s"
                }
                """.formatted(sourceRef);
    }

    @Test
    void createDeploymentShouldPersistQueuedResource() throws Exception {
        ProjectEntity project = createProject();

        mockMvc.perform(post(
                        API_URL + "/projects/{projectId}/deployments",
                        project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithSourceRef("main")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.projectId")
                        .value(project.getId().toString()))
                .andExpect(jsonPath("$.sourceRepositoryUrl")
                        .value("https://github.com/example/hello-world"))
                .andExpect(jsonPath("$.sourceRef").value("main"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(deploymentRepository.count()).isEqualTo(1L);
    }

    @Test
    void createDeploymentForUnknownProjectShouldReturnNotFound()
            throws Exception {

        mockMvc.perform(post(
                        API_URL + "/projects/{projectId}/deployments",
                        UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithSourceRef("main")))
                .andExpect(status().isNotFound());

        assertThat(deploymentRepository.count()).isZero();
    }

    @Test
    void blankSourceRefShouldBeRejected() throws Exception {
        ProjectEntity project = createProject();

        mockMvc.perform(post(
                        API_URL + "/projects/{projectId}/deployments",
                        project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestWithSourceRef("   ")))
                .andExpect(status().isBadRequest());

        assertThat(deploymentRepository.count()).isZero();
    }

    @Test
    void listDeploymentsShouldReturnProjectHistory() throws Exception {
        ProjectEntity project = createProject();

        deploymentRepository.save(new DeploymentEntity(project, "main"));

        mockMvc.perform(get(
                        API_URL + "/projects/{projectId}/deployments",
                        project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceRef").value("main"))
                .andExpect(jsonPath("$[0].status").value("QUEUED"));
    }

    @Test
    void listingDeploymentsForUnknownProjectShouldReturnNotFound()
            throws Exception {

        mockMvc.perform(get(
                        API_URL + "/projects/{projectId}/deployments",
                        UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDeploymentShouldReturnStoredResource() throws Exception {
        ProjectEntity project = createProject();
        DeploymentEntity saved = deploymentRepository.save(
                new DeploymentEntity(project, "main"));

        mockMvc.perform(get(
                        API_URL + "/deployments/{deploymentId}",
                        saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.projectId")
                        .value(project.getId().toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void getUnknownDeploymentShouldReturnNotFound() throws Exception {
        mockMvc.perform(get(
                        API_URL + "/deployments/{deploymentId}",
                        UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void logsShouldBeOrderedAndRetrievedAcrossPages() throws Exception {
        ProjectEntity project = createProject();

        DeploymentEntity deployment = deploymentRepository.save(
                new DeploymentEntity(project, "main"));

        deploymentLogService.append(
                deployment.getId(),
                DeploymentLogStage.SOURCE_CHECKOUT,
                DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.INFO,
                "Checking out source"
        ).orElseThrow();

        deploymentLogService.append(
                deployment.getId(),
                DeploymentLogStage.BUILD,
                DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.INFO,
                "Build started"
        ).orElseThrow();

        deploymentLogService.append(
                deployment.getId(),
                DeploymentLogStage.BUILD,
                DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.INFO,
                "Build finished"
        ).orElseThrow();

        mockMvc.perform(get(
                        API_URL + "/deployments/{deploymentId}/logs",
                        deployment.getId())
                        .param("afterSequence", "0")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentId")
                        .value(deployment.getId().toString()))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.entries[0].message")
                        .value("Checking out source"))
                .andExpect(jsonPath("$.entries[1].sequenceNumber").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextAfterSequence").value(2));

        mockMvc.perform(get(
                        API_URL + "/deployments/{deploymentId}/logs",
                        deployment.getId())
                        .param("afterSequence", "2")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].sequenceNumber").value(3))
                .andExpect(jsonPath("$.entries[0].message")
                        .value("Build finished"))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextAfterSequence").value(3));
    }

    @Test
    void deploymentWithoutLogsShouldReturnEmptyPage() throws Exception {
        ProjectEntity project = createProject();

        DeploymentEntity deployment = deploymentRepository.save(
                new DeploymentEntity(project, "main"));

        mockMvc.perform(get(
                        API_URL + "/deployments/{deploymentId}/logs",
                        deployment.getId())
                        .param("afterSequence", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.nextAfterSequence").value(42))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void logsForUnknownDeploymentShouldReturnNotFound() throws Exception {
        mockMvc.perform(get(
                        API_URL + "/deployments/{deploymentId}/logs",
                        UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void stopUnknownDeploymentShouldReturnNotFound() throws Exception {
        mockMvc.perform(post(
                        API_URL + "/deployments/{deploymentId}/stop",
                        UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void stopQueuedDeploymentShouldReturnConflict() throws Exception {
        ProjectEntity project = createProject();
        DeploymentEntity deployment = deploymentRepository.save(
                new DeploymentEntity(project, "main"));

        mockMvc.perform(post(
                        API_URL + "/deployments/{deploymentId}/stop",
                        deployment.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Only a succeeded deployment with runtime state RUNNING can be stopped."));
    }

    @Test
    void stopRunningDeploymentWhenLocalRuntimeIsDisabledShouldReturnServiceUnavailable()
            throws Exception {
        ProjectEntity project = createProject();
        DeploymentEntity deployment = new DeploymentEntity(project, "main");
        deployment.transitionTo(DeploymentStatus.BUILDING);
        deployment.transitionTo(DeploymentStatus.STARTING);
        deployment.transitionTo(DeploymentStatus.HEALTH_CHECK);
        deployment.markHealthCheckPassed("http://00000000-0000-0000-0000-000000000001.localhost:8080");
        deployment = deploymentRepository.save(deployment);

        mockMvc.perform(post(
                        API_URL + "/deployments/{deploymentId}/stop",
                        deployment.getId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("The local runtime is disabled or unavailable; Foundry cannot stop this application."));
    }

    @Test
    void invalidLogCursorOrPageSizeShouldBeRejected() throws Exception {
        ProjectEntity project = createProject();

        DeploymentEntity deployment = deploymentRepository.save(
                new DeploymentEntity(project, "main"));

        mockMvc.perform(get(
                        API_URL + "/deployments/{deploymentId}/logs",
                        deployment.getId())
                        .param("afterSequence", "-1"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(
                        API_URL + "/deployments/{deploymentId}/logs",
                        deployment.getId())
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
    }
}