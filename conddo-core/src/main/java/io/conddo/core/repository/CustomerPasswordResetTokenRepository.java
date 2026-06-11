package io.conddo.core.repository;

import io.conddo.core.domain.CustomerPasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerPasswordResetTokenRepository
        extends JpaRepository<CustomerPasswordResetToken, UUID> {

    Optional<CustomerPasswordResetToken> findBySelector(String selector);
}
