package io.conddo.studio.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Studio's read-only mirror of {@code public.users} (Infrastructure §23.2).
 * The platform's {@code User} entity is the source of truth — Studio sees
 * the rows because it connects as {@code conddo_owner}, the row-policy owner.
 *
 * <p>Maps only the columns Studio admins use day-to-day. Sensitive columns
 * ({@code password_hash}, the auth lockout counters, refresh-token state)
 * are intentionally NOT mapped here.
 */
@Entity
@Table(name = "users", schema = "public")
public class PlatformUser {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String email;

    @Column(name = "full_name")
    private String fullName;

    @Column(nullable = false)
    private String role;

    @Column
    private String phone;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "google_sub")
    private String googleSub;

    protected PlatformUser() {
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void changeRole(String role) {
        if (role != null && !role.isBlank()) {
            this.role = role;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRole() {
        return role;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getGoogleSub() {
        return googleSub;
    }
}
