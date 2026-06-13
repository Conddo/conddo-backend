package io.conddo.core.repository;

import io.conddo.core.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Cross-tenant lookup for the invite-token preview/accept flow — the
     * caller has no JWT yet, so we can't pre-bind a tenant. The token hash
     * is globally unique (V49 partial UNIQUE index), so this is safe.
     */
    @Query(value = "SELECT * FROM users WHERE invite_token_hash = :tokenHash", nativeQuery = true)
    Optional<User> findByInviteTokenHashCrossTenant(@Param("tokenHash") String tokenHash);

    /**
     * Cross-tenant email lookup — used at signup + invite to enforce
     * the global email UNIQUE (V50). Bypasses RLS via a native query
     * since signup runs before any tenant is bound.
     */
    @Query(value = "SELECT * FROM users WHERE email = :email LIMIT 1", nativeQuery = true)
    Optional<User> findFirstByEmailCrossTenant(@Param("email") String email);
}
