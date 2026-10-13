package dev.foundry.api.project;

import dev.foundry.api.project.dto.ProjectCreateRequest;
import dev.foundry.api.project.dto.ProjectResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> register(
            @Valid @RequestBody ProjectCreateRequest request) {

        ProjectResponse created = projectService.register(request);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public List<ProjectResponse> findAll() {
        return projectService.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> findById(
            @PathVariable UUID id) {

        return projectService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}