package dev.foundry.api.deployment;

import dev.foundry.api.deployment.dto.DeploymentCreateRequest;
import dev.foundry.api.deployment.dto.DeploymentResponse;
import dev.foundry.api.project.ProjectEntity;
import dev.foundry.api.project.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeploymentService {

    private final DeploymentRepository deploymentRepository;
    private final ProjectRepository projectRepository;

    public DeploymentService(
            DeploymentRepository deploymentRepository,
            ProjectRepository projectRepository) {
        this.deploymentRepository = deploymentRepository;
        this.projectRepository = projectRepository;
    }

    public Optional<DeploymentResponse> createForProject(
            UUID projectId,
            DeploymentCreateRequest request) {

        return projectRepository.findById(projectId)
                .map(project ->
                        new DeploymentEntity(project, request.sourceRef()))
                .map(deploymentRepository::save)
                .map(this::toResponse);
    }

    public Optional<List<DeploymentResponse>> findAllForProject(
            UUID projectId) {

        if (!projectRepository.existsById(projectId)) {
            return Optional.empty();
        }

        List<DeploymentResponse> deployments = deploymentRepository
                .findAllByProject_IdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();

        return Optional.of(deployments);
    }

    public Optional<DeploymentResponse> findById(UUID deploymentId) {
        return deploymentRepository.findById(deploymentId)
                .map(this::toResponse);
    }

    private DeploymentResponse toResponse(DeploymentEntity deployment) {
        return new DeploymentResponse(
                deployment.getId(),
                deployment.getProjectId(),
                deployment.getSourceRepositoryUrl(),
                deployment.getSourceRef(),
                deployment.getStatus(),
                deployment.getCreatedAt(),
                deployment.getStartedAt(),
                deployment.getCompletedAt(),
                deployment.getFailureReason()
        );
    }
}