package dev.foundry.api.deployment.log;

import dev.foundry.api.deployment.DeploymentEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "deployment_log_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_deployment_log_sequence",
                columnNames = {"deployment_id", "sequence_number"}
        ),
        indexes = @Index(
                name = "idx_deployment_log_cursor",
                columnList = "deployment_id, sequence_number"
        )
)
public class DeploymentLogEntry {

    public static final int MAX_MESSAGE_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "deployment_id",
            nullable = false,
            updatable = false
    )
    private DeploymentEntity deployment;

    @Column(
            name = "sequence_number",
            nullable = false,
            updatable = false
    )
    private long sequenceNumber;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private DeploymentLogStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private DeploymentLogStream stream;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private DeploymentLogSeverity severity;

    @Column(nullable = false, length = MAX_MESSAGE_LENGTH, updatable = false)
    private String message;

    protected DeploymentLogEntry() {
        // Required by JPA.
    }

    public DeploymentLogEntry(
            DeploymentEntity deployment,
            long sequenceNumber,
            DeploymentLogStage stage,
            DeploymentLogStream stream,
            DeploymentLogSeverity severity,
            String message) {

        this.deployment = Objects.requireNonNull(
                deployment, "Deployment is required");

        if (sequenceNumber < 1) {
            throw new IllegalArgumentException(
                    "Sequence number must be positive");
        }

        this.sequenceNumber = sequenceNumber;
        this.stage = Objects.requireNonNull(stage, "Stage is required");
        this.stream = Objects.requireNonNull(stream, "Stream is required");
        this.severity = Objects.requireNonNull(
                severity, "Severity is required");

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "Log message is required");
        }

        String normalizedMessage = message.trim();

        if (normalizedMessage.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "Log message cannot exceed "
                            + MAX_MESSAGE_LENGTH + " characters");
        }

        this.message = normalizedMessage;
        this.timestamp = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeploymentId() {
        return deployment.getId();
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public DeploymentLogStage getStage() {
        return stage;
    }

    public DeploymentLogStream getStream() {
        return stream;
    }

    public DeploymentLogSeverity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }
}