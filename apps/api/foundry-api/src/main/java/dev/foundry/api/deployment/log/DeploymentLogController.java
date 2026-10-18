package dev.foundry.api.deployment.log;

import dev.foundry.api.deployment.dto.DeploymentLogsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/deployments/{deploymentId}/logs")
public class DeploymentLogController {

    private final DeploymentLogService deploymentLogService;

    public DeploymentLogController(
            DeploymentLogService deploymentLogService) {
        this.deploymentLogService = deploymentLogService;
    }

    @GetMapping
    public ResponseEntity<DeploymentLogsResponse> findLogs(
            @PathVariable UUID deploymentId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "50") int limit) {

        if (afterSequence < 0
                || limit < 1
                || limit > DeploymentLogService.MAX_PAGE_SIZE) {
            return ResponseEntity.badRequest().build();
        }

        return deploymentLogService.findLogs(
                        deploymentId,
                        afterSequence,
                        limit
                )
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}