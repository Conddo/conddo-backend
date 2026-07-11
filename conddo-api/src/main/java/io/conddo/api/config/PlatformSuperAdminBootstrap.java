package io.conddo.api.config;

import io.conddo.core.auth.InternalRole;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.domain.StaffUser;
import io.conddo.core.repository.StaffUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plants — and keeps — a SUPER_ADMIN account in {@code staff_users} so the
 * platform owner has a way into the admin dashboard on studio.getconddo.com
 * and the cross-tenant admin surfaces on this API.
 *
 * <p>Runs whenever {@code CONDDO_BOOTSTRAP_SUPERADMIN_EMAIL} and
 * {@code CONDDO_BOOTSTRAP_SUPERADMIN_PASSWORD} are both set:
 * <ul>
 *   <li>If no staff row exists with that email → creates as SUPER_ADMIN.</li>
 *   <li>If a row exists → resets the password to match the env var, force-
 *       sets role back to SUPER_ADMIN, and marks the account active.
 *       Idempotent — safe on every boot.</li>
 * </ul>
 *
 * <p>The env-var pair acts as a permanent recovery path: clearing them turns
 * this runner into a no-op without touching an existing row. Same trust
 * boundary as any deploy secret — Render dashboard access = root.
 */
@Component
public class PlatformSuperAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformSuperAdminBootstrap.class);

    private final StaffUserRepository staffUserRepository;
    private final PasswordHasher passwordHasher;
    private final String email;
    private final String password;
    private final String fullName;

    public PlatformSuperAdminBootstrap(
            StaffUserRepository staffUserRepository,
            PasswordHasher passwordHasher,
            @Value("${conddo.bootstrap.superadmin.email:}") String email,
            @Value("${conddo.bootstrap.superadmin.password:}") String password,
            @Value("${conddo.bootstrap.superadmin.name:Platform Super Admin}") String fullName) {
        this.staffUserRepository = staffUserRepository;
        this.passwordHasher = passwordHasher;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.debug("Platform superadmin bootstrap skipped — env vars not set.");
            return;
        }
        if (password.length() < 8) {
            log.warn("Platform superadmin bootstrap skipped — password must be ≥ 8 chars.");
            return;
        }
        String trimmedEmail = email.trim().toLowerCase();
        String trimmedName = fullName.trim();
        String hash = passwordHasher.hash(password);
        String role = InternalRole.SUPER_ADMIN.name();

        staffUserRepository.findByEmail(trimmedEmail).ifPresentOrElse(
                existing -> {
                    existing.resetPasswordHash(hash);
                    existing.changeInternalRole(role);
                    existing.setActive(true);
                    staffUserRepository.save(existing);
                    log.info("Platform superadmin bootstrap reset SUPER_ADMIN <{}>.", trimmedEmail);
                },
                () -> {
                    staffUserRepository.save(new StaffUser(trimmedEmail, hash, trimmedName, role));
                    log.info("Platform superadmin bootstrap created SUPER_ADMIN <{}>.", trimmedEmail);
                });
    }
}
