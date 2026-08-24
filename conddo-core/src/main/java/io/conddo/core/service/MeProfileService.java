package io.conddo.core.service;

import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Tenant;
import io.conddo.core.domain.User;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.repository.UserRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Mobile-facing profile write operations behind {@code PATCH /api/v1/me}
 * and {@code POST /api/v1/me/change-password}. Kept separate from
 * {@link MeService} (read-side only) so read + write concerns don't
 * cross-contaminate.
 */
@Service
public class MeProfileService {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final TenantSession tenantSession;
    private final PasswordHasher passwordHasher;

    public MeProfileService(UserRepository userRepository,
                            TenantRepository tenantRepository,
                            TenantSession tenantSession,
                            PasswordHasher passwordHasher) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSession = tenantSession;
        this.passwordHasher = passwordHasher;
    }

    /**
     * Update the current user's full name and/or their tenant's business
     * name. Both fields are optional — a caller can hit this with just
     * one to update one thing. Blank values are ignored (name is a
     * "presence required" field, so a client that means "clear it"
     * should send the request differently).
     */
    @Transactional
    public MeService.Identity updateProfile(UUID userId, String fullName, String businessName) {
        tenantSession.bind();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Current user not found"));
        Tenant tenant = tenantRepository.findById(TenantContext.require())
                .orElseThrow(() -> new NotFoundException("Tenant not found"));

        if (fullName != null && !fullName.isBlank()) {
            user.setFullName(fullName.trim());
            userRepository.save(user);
        }
        if (businessName != null && !businessName.isBlank()) {
            tenant.rename(businessName);
            tenantRepository.save(tenant);
        }
        return new MeService.Identity(user, tenant);
    }

    /**
     * Change the current user's password. Requires the current password
     * to prevent a stolen-JWT attack from silently rotating credentials.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        tenantSession.bind();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Current user not found"));

        if (currentPassword == null || !passwordHasher.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }
        if (passwordHasher.matches(newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must be different from the current one");
        }
        user.changePassword(passwordHasher.hash(newPassword));
        userRepository.save(user);
    }
}
