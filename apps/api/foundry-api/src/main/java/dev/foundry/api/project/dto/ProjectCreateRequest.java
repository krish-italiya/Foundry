package dev.foundry.api.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectCreateRequest(

        @NotBlank(message = "Project name is required")
        @Size(max = 80, message = "Project name cannot exceed 80 characters")
        String name,

        @NotBlank(message = "Repository URL is required")
        @Size(max = 2048, message = "Repository URL cannot exceed 2048 characters")
        String repositoryUrl

) {
}