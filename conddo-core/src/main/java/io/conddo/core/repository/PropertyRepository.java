package io.conddo.core.repository;

import io.conddo.core.domain.Property;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RLS-scoped to the current tenant. Includes queries for the dashboard
 * list view, the public catalog (filter by status = available), and
 * per-status counters for the header widget.
 */
public interface PropertyRepository extends JpaRepository<Property, UUID>, JpaSpecificationExecutor<Property> {

    /** Dashboard read — RLS scopes this to the bound tenant. */
    Page<Property> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Public catalog resolver — the tenant's website + the AI classifier
     *  use this to snapshot the current listings. Only available + public. */
    @Query("select p from Property p where p.status = 'available' and p.isPublic = true order by p.featured desc, p.createdAt desc")
    List<Property> findPublicAvailable();

    /** Public catalog by slug — for individual property detail pages. */
    Optional<Property> findBySlugAndIsPublicTrue(String slug);

    long countByStatus(String status);
}
