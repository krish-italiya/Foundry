package dev.foundry.api.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeploymentRepository
        extends JpaRepository<DeploymentEntity, UUID> {

    List<DeploymentEntity> findAllByProject_IdOrderByCreatedAtDesc(
            UUID projectId
    );
}
