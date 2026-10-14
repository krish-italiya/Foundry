package dev.foundry.api.deployment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeploymentCreateRequest(

        @NotBlank(message = "Source ref is required")
        @Size(max = 255, message = "Source ref cannot exceed 255 characters")
        String sourceRef

) {
}