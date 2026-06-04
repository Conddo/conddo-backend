package io.conddo.studio.repository;

import io.conddo.studio.domain.SiteSection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SiteSectionRepository extends JpaRepository<SiteSection, UUID> {

    List<SiteSection> findByPageIdOrderByOrderIndexAsc(UUID pageId);
}
