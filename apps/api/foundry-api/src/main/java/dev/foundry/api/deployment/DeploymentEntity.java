package dev.foundry.api.deployment;

import dev.foundry.api.project.ProjectEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "deployments")
public class DeploymentEntity {

    private static final int MAX_FAILURE_REASON_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ProjectEntity project;

    @Column(
            name = "source_repository_url",
            nullable = false,
            length = 2048,
            updatable = false
    )
    private String sourceRepositoryUrl;

    @Column(name = "source_ref", nullable = false, length = 255, updatable = false)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DeploymentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = MAX_FAILURE_REASON_LENGTH)
    private String failureReason;

    protected DeploymentEntity() {
        // Required by JPA.
    }

    public DeploymentEntity(ProjectEntity project, String sourceRef) {
        this.project = Objects.requireNonNull(project, "Project is required");

        if (project.getRepositoryUrl() == null
                || project.getRepositoryUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "Project repository URL is required"
            );
        }

        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException(
                    "Source ref is required"
            );
        }

        this.sourceRepositoryUrl = project.getRepositoryUrl();
        this.sourceRef = sourceRef.trim();
        this.status = DeploymentStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    public void transitionTo(DeploymentStatus nextStatus) {
        Objects.requireNonNull(nextStatus, "Next status is required");

        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot transition from terminal status: " + status
            );
        }

        boolean allowed = switch (status) {
            case QUEUED -> nextStatus == DeploymentStatus.BUILDING;
            case BUILDING -> nextStatus == DeploymentStatus.STARTING;
            case STARTING -> nextStatus == DeploymentStatus.HEALTH_CHECK;
            case HEALTH_CHECK, SUCCEEDED, FAILED -> false;
        };

        if (!allowed) {
            throw new IllegalStateException(
                    "Invalid deployment transition: "
                            + status + " -> " + nextStatus
            );
        }

        if (nextStatus == DeploymentStatus.BUILDING && startedAt == null) {
            startedAt = Instant.now();
        }

        status = nextStatus;
    }

    public void markHealthCheckPassed() {
        if (status != DeploymentStatus.HEALTH_CHECK) {
            throw new IllegalStateException(
                    "Deployment can succeed only after entering HEALTH_CHECK"
            );
        }

        status = DeploymentStatus.SUCCEEDED;
        completedAt = Instant.now();
    }

    public void fail(String reason) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot fail a deployment in terminal status: " + status
            );
        }

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "A failure reason is required"
            );
        }

        String normalizedReason = reason.trim();

        if (normalizedReason.length() > MAX_FAILURE_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "Failure reason cannot exceed "
                            + MAX_FAILURE_REASON_LENGTH + " characters"
            );
        }

        status = DeploymentStatus.FAILED;
        failureReason = normalizedReason;
        completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return project.getId();
    }

    public String getSourceRepositoryUrl() {
        return sourceRepositoryUrl;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public DeploymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }
}