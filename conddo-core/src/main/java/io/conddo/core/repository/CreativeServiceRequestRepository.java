package io.conddo.core.repository;

import io.conddo.core.domain.CreativeServiceRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RLS-scoped. The Studio-delivered webhook path looks up a request by id
 * inside the {@code app.public_resolver} carve-out (V25 pattern) — see
 * {@link CreativeServiceRequest} RLS in V29.
 */
public interface CreativeServiceRequestRepository extends JpaRepository<CreativeServiceRequest, UUID> {

    List<CreativeServiceRequest> findByOrderByCreatedAtDesc();

    Optional<CreativeServiceRequest> findByPaymentReference(String reference);
}
