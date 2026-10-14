package dev.foundry.api.deployment;

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

    @BeforeEach
    void cleanDatabase() {
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
}