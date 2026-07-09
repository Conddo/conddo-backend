package io.conddo.core.repository;

import io.conddo.core.domain.PropertyViewing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PropertyViewingRepository extends JpaRepository<PropertyViewing, UUID> {

    /** Upcoming viewings — dashboard widget. */
    List<PropertyViewing> findByScheduledAtGreaterThanEqualOrderByScheduledAtAsc(OffsetDateTime from);

    /** For a specific property — its schedule. */
    List<PropertyViewing> findByPropertyIdOrderByScheduledAtDesc(UUID propertyId);

    /** For an agent's own schedule. */
    List<PropertyViewing> findByAgentIdAndScheduledAtGreaterThanEqualOrderByScheduledAtAsc(
            UUID agentId, OffsetDateTime from);
}
