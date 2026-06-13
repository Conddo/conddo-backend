package io.conddo.studio.config;

import io.conddo.studio.staff.StaffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Plants — and keeps — the platform owner's ADMIN staff member at startup so
 * the team always has a way in.
 *
 * <p>Runs whenever {@code STUDIO_BOOTSTRAP_ADMIN_EMAIL} and
 * {@code STUDIO_BOOTSTRAP_ADMIN_PASSWORD} are both set:
 * <ul>
 *   <li>If no staff row exists with that email → creates them as ADMIN.</li>
 *   <li>If a row exists → resets the password to match the env var, force-
 *       sets role back to ADMIN, and marks the account active. Idempotent.</li>
 * </ul>
 *
 * <p>This makes the env-var pair a permanent recovery path — the platform
 * owner keeps the credentials in their password manager and the account is
 * guaranteed to honour them after every redeploy. Clearing the env vars
 * turns the bootstrap back into a no-op without affecting the existing row.
 *
 * <p>Anyone with Render dashboard access can therefore reset this admin's
 * password by changing the env var. That is by design — the same access
 * already implies root over the deploy — and is the same trust boundary
 * Django's {@code createsuperuser} pattern relies on.
 *
 * <p>Password hashing goes through the live {@code BCryptPasswordEncoder(12)}
 * bean via {@link StaffService#upsertAdmin}, guaranteed to match what
 * {@link io.conddo.studio.auth.StudioAuthService#login} verifies against.
 */
@Component
public class StudioAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StudioAdminBootstrap.class);

    private final StaffService staffService;
    private final String email;
    private final String password;
    private final String fullName;

    public StudioAdminBootstrap(
            StaffService staffService,
            @Value("${studio.bootstrap.admin.email:}") String email,
            @Value("${studio.bootstrap.admin.password:}") String password,
            @Value("${studio.bootstrap.admin.name:Platform Admin}") String fullName) {
        this.staffService = staffService;
        this.email = email;
        this.password = password;
        this.fullName = fullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            log.debug("Studio admin bootstrap skipped — STUDIO_BOOTSTRAP_ADMIN_EMAIL/PASSWORD not set.");
            return;
        }
        if (password.length() < 8) {
            log.warn("Studio admin bootstrap skipped — STUDIO_BOOTSTRAP_ADMIN_PASSWORD must be at least 8 characters.");
            return;
        }
        staffService.upsertAdmin(email.trim(), fullName.trim(), password);
        log.info("Studio admin bootstrap upserted ADMIN <{}>. " +
                "Env vars stay set as a permanent recovery path; rotate by changing the env var.", email);
    }
}
