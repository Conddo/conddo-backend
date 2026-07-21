package io.conddo.core.repository;

import io.conddo.core.domain.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    Optional<PaymentEvent> findByProviderAndProviderEventId(String provider, String providerEventId);

    /** Reprocessor — pick up events that failed on first handling. */
    List<PaymentEvent> findTop50ByProcessedFalseOrderByReceivedAtAsc();
}
