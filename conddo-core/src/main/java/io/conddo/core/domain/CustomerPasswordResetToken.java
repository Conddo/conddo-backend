package io.conddo.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One-shot password reset token issued to a public-website customer
 * (PHARMACY_PUBLIC_API_SPEC §2). Mirrors the staff
 * {@code PasswordResetToken} but lives in its own table — customers
 * and staff are separate identity scopes.
 */
@Entity
@Table(name = "customer_password_reset_tokens")
public class CustomerPasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 64, unique = true)
    private String selector;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    protected CustomerPasswordResetToken() {
    }

    public CustomerPasswordResetToken(UUID customerId, UUID tenantId, String selector,
                                       String tokenHash, OffsetDateTime expiresAt) {
        this.customerId = customerId;
        this.tenantId = tenantId;
        this.selector = selector;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSelector() {
        return selector;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public boolean isUsable(OffsetDateTime now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(OffsetDateTime at) {
        this.usedAt = at;
    }
}
