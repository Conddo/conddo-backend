package io.conddo.core.repository;

import io.conddo.core.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * As with {@code CustomerRepository}, there is no {@code findByTenantId(...)}:
 * RLS scopes every query to the bound tenant. {@code email} is unique only
 * <em>per tenant</em>, so {@link #findByEmail} returns at most one row once a
 * tenant is bound to the transaction.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /** Look up a Google-linked user (within the RLS-bound tenant). */
    Optional<User> findByGoogleSub(String googleSub);

    /**
     * Find the original signup owner of the current (RLS-bound) tenant —
     * the longest-tenured user with the given role. Used by the order-notify
     * listener to decide which email/phone to alert when a public-website
     * order lands.
     */
    Optional<User> findFirstByRoleOrderByCreatedAtAsc(String role);
}
