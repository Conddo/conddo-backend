package io.conddo.studio.repository;

import io.conddo.studio.domain.SitePage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SitePageRepository extends JpaRepository<SitePage, UUID> {

    List<SitePage> findBySiteIdOrderByOrderIndexAsc(UUID siteId);

    Optional<SitePage> findBySiteIdAndHomeTrue(UUID siteId);

    /** Conflict check on slug uniqueness within a site (§21 schema constraint). */
    Optional<SitePage> findBySiteIdAndSlug(UUID siteId, String slug);

    long countBySiteId(UUID siteId);
}
