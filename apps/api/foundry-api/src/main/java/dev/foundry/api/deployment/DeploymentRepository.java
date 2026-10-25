package dev.foundry.api.deployment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository
        extends JpaRepository<DeploymentEntity, UUID> {

    List<DeploymentEntity> findAllByProject_IdOrderByCreatedAtDesc(
            UUID projectId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select deployment
            from DeploymentEntity deployment
            where deployment.id = :deploymentId
            """)
    Optional<DeploymentEntity> findByIdForUpdate(
            @Param("deploymentId") UUID deploymentId
    );

    Optional<DeploymentEntity> findFirstByStatusOrderByCreatedAtAsc(
            DeploymentStatus status
    );

    List<DeploymentEntity> findAllByStatusOrderByCreatedAtAsc(
            DeploymentStatus status
    );
}