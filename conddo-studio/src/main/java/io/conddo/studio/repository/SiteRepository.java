package io.conddo.studio.repository;

import io.conddo.studio.domain.Site;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SiteRepository extends JpaRepository<Site, UUID> {

    /** Builder is keyed by job — one Site per Job. */
    Optional<Site> findByJobId(UUID jobId);
}
