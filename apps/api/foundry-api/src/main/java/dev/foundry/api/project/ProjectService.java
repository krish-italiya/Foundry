package dev.foundry.api.project;

import dev.foundry.api.project.dto.ProjectCreateRequest;
import dev.foundry.api.project.dto.ProjectResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public ProjectResponse register(ProjectCreateRequest request) {
        ProjectEntity project = new ProjectEntity(
                request.name().trim(),
                request.repositoryUrl().trim()
        );

        ProjectEntity savedProject = projectRepository.save(project);

        return toResponse(savedProject);
    }

    public List<ProjectResponse> findAll() {
        return projectRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<ProjectResponse> findById(UUID id) {
        return projectRepository.findById(id)
                .map(this::toResponse);
    }

    private ProjectResponse toResponse(ProjectEntity project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getRepositoryUrl(),
                project.getCreatedAt()
        );
    }
}