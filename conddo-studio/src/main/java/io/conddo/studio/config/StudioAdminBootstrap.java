package io.conddo.studio.config;

import io.conddo.studio.repository.StaffRepository;
import io.conddo.studio.staff.StaffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Plants the first ADMIN staff member at startup so the team has a way in.
 *
 * <p>Solves the chicken-and-egg in the staff lifecycle: every staff-creation
 * route requires an authenticated ADMIN, but a fresh deploy has no staff. This
 * bean fills that gap exactly once.
 *
 * <p>Runs only when:
 * <ul>
 *   <li>{@code STUDIO_BOOTSTRAP_ADMIN_EMAIL} and {@code STUDIO_BOOTSTRAP_ADMIN_PASSWORD} are set, AND</li>
 *   <li>{@code studio.staff} is empty (idempotent — already-bootstrapped envs are skipped).</li>
 * </ul>
 *
 * <p>Uses {@link StaffService#create} so the password is hashed by the live
 * {@code BCryptPasswordEncoder(12)} bean — guaranteed to match what
 * {@code StudioAuthService.login} checks. Once you've signed in, clear the env
 * vars and rotate the password from the Staff page.
 */
@Component
public class StudioAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StudioAdminBootstrap.class);

    private final StaffRepository staffRepository;
    private final StaffService staffService;
    private final String email;
    private final String password;
    private final String fullName;

    public StudioAdminBootstrap(
            StaffRepository staffRepository,
            StaffService staffService,
            @Value("${studio.bootstrap.admin.email:}") String email,
            @Value("${studio.bootstrap.admin.password:}") String password,
            @Value("${studio.bootstrap.admin.name:Studio Admin}") String fullName) {
        this.staffRepository = staffRepository;
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
        if (staffRepository.count() > 0) {
            log.info("Studio admin bootstrap skipped — staff table already populated.");
            return;
        }
        staffService.create(email.trim(), fullName.trim(), "ADMIN", List.of(), password);
        log.info("Studio admin bootstrap created ADMIN <{}>. " +
                "Remove STUDIO_BOOTSTRAP_ADMIN_* env vars and rotate the password from the Staff page.", email);
    }
}
