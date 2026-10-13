package dev.foundry.api.project;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectControllerIntegrationTest {

    private static final String PROJECTS_URL = "/api/v1/projects";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void cleanDatabase() {
        projectRepository.deleteAll();
    }

    @Test
    void createProjectShouldPersistAndReturnCreatedResource() throws Exception {
        String request = """
                {
                  "name": "hello-world",
                  "repositoryUrl": "https://github.com/example/hello-world"
                }
                """;

        mockMvc.perform(post(PROJECTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("hello-world"))
                .andExpect(jsonPath("$.repositoryUrl")
                        .value("https://github.com/example/hello-world"))
                .andExpect(jsonPath("$.createdAt").exists());

        assertThat(projectRepository.count()).isEqualTo(1L);
    }

    @Test
    void blankProjectNameShouldBeRejected() throws Exception {
        String request = """
                {
                  "name": "   ",
                  "repositoryUrl": "https://github.com/example/hello-world"
                }
                """;

        mockMvc.perform(post(PROJECTS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());

        assertThat(projectRepository.count()).isZero();
    }

    @Test
    void unknownProjectIdShouldReturnNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();

        mockMvc.perform(get(PROJECTS_URL + "/" + unknownId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listProjectsShouldReturnStoredProjects() throws Exception {
        projectRepository.save(new ProjectEntity(
                "demo-project",
                "https://github.com/example/demo-project"
        ));

        mockMvc.perform(get(PROJECTS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("demo-project"))
                .andExpect(jsonPath("$[0].repositoryUrl")
                        .value("https://github.com/example/demo-project"));
    }
}