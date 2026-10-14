package dev.foundry.api.deployment;

import dev.foundry.api.deployment.dto.DeploymentCreateRequest;
import dev.foundry.api.deployment.dto.DeploymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DeploymentController {

    private final DeploymentService deploymentService;

    public DeploymentController(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @PostMapping("/projects/{projectId}/deployments")
    public ResponseEntity<DeploymentResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody DeploymentCreateRequest request) {

        Optional<DeploymentResponse> result =
                deploymentService.createForProject(projectId, request);

        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        DeploymentResponse created = result.get();

        URI location = ServletUriComponentsBuilder
                .fromCurrentContextPath()
                .path("/api/v1/deployments/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/projects/{projectId}/deployments")
    public ResponseEntity<List<DeploymentResponse>> findForProject(
            @PathVariable UUID projectId) {

        Optional<List<DeploymentResponse>> result =
                deploymentService.findAllForProject(projectId);

        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(result.get());
    }

    @GetMapping("/deployments/{deploymentId}")
    public ResponseEntity<DeploymentResponse> findById(
            @PathVariable UUID deploymentId) {

        return deploymentService.findById(deploymentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}