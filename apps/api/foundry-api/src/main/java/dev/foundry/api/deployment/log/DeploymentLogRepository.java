package dev.foundry.api.deployment.log;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentLogRepository
        extends JpaRepository<DeploymentLogEntry, UUID> {

    List<DeploymentLogEntry>
    findByDeployment_IdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID deploymentId,
            long afterSequence,
            Pageable pageable
    );

    Optional<DeploymentLogEntry>
    findTopByDeployment_IdOrderBySequenceNumberDesc(UUID deploymentId);
}