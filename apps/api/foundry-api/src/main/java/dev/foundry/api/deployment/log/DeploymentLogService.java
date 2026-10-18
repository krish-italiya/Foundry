package dev.foundry.api.deployment.log;

import dev.foundry.api.deployment.DeploymentEntity;
import dev.foundry.api.deployment.DeploymentRepository;
import dev.foundry.api.deployment.dto.DeploymentLogEntryResponse;
import dev.foundry.api.deployment.dto.DeploymentLogsResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeploymentLogService {

    public static final int MAX_PAGE_SIZE = 100;

    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogRepository logRepository;

    public DeploymentLogService(
            DeploymentRepository deploymentRepository,
            DeploymentLogRepository logRepository) {
        this.deploymentRepository = deploymentRepository;
        this.logRepository = logRepository;
    }

    @Transactional
    public Optional<DeploymentLogEntryResponse> append(
            UUID deploymentId,
            DeploymentLogStage stage,
            DeploymentLogStream stream,
            DeploymentLogSeverity severity,
            String message) {

        Optional<DeploymentEntity> lockedDeployment =
                deploymentRepository.findByIdForUpdate(deploymentId);

        if (lockedDeployment.isEmpty()) {
            return Optional.empty();
        }

        DeploymentEntity deployment = lockedDeployment.get();

        long nextSequence = logRepository
                .findTopByDeployment_IdOrderBySequenceNumberDesc(deploymentId)
                .map(entry -> entry.getSequenceNumber() + 1)
                .orElse(1L);

        DeploymentLogEntry entry = new DeploymentLogEntry(
                deployment,
                nextSequence,
                stage,
                stream,
                severity,
                message
        );

        return Optional.of(toResponse(logRepository.save(entry)));
    }

    @Transactional(readOnly = true)
    public Optional<DeploymentLogsResponse> findLogs(
            UUID deploymentId,
            long afterSequence,
            int limit) {

        if (afterSequence < 0) {
            throw new IllegalArgumentException(
                    "afterSequence cannot be negative");
        }

        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_PAGE_SIZE);
        }

        if (!deploymentRepository.existsById(deploymentId)) {
            return Optional.empty();
        }

        // Fetch one extra entry so we can tell whether another page exists.
        List<DeploymentLogEntry> fetched = logRepository
                .findByDeployment_IdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
                        deploymentId,
                        afterSequence,
                        PageRequest.of(0, limit + 1)
                );

        boolean hasMore = fetched.size() > limit;

        List<DeploymentLogEntry> page = hasMore
                ? fetched.subList(0, limit)
                : fetched;

        List<DeploymentLogEntryResponse> entries = page.stream()
                .map(DeploymentLogService::toResponse)
                .toList();

        long nextAfterSequence = page.isEmpty()
                ? afterSequence
                : page.getLast().getSequenceNumber();

        return Optional.of(new DeploymentLogsResponse(
                deploymentId,
                entries,
                nextAfterSequence,
                hasMore
        ));
    }

    private static DeploymentLogEntryResponse toResponse(
            DeploymentLogEntry entry) {

        return new DeploymentLogEntryResponse(
                entry.getId(),
                entry.getDeploymentId(),
                entry.getSequenceNumber(),
                entry.getTimestamp(),
                entry.getStage(),
                entry.getStream(),
                entry.getSeverity(),
                entry.getMessage()
        );
    }
}