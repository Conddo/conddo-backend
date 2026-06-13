package io.conddo.studio.staff;

import io.conddo.studio.common.ConflictException;
import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.Staff;
import io.conddo.studio.repository.StaffRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Studio staff directory (Infrastructure §12) — used by /me and admin staff management. */
@Service
public class StaffService {

    private static final Set<String> ROLES =
            Set.of("DEVELOPER", "DESIGNER", "WRITER", "QA_REVIEWER", "TEAM_LEAD", "ADMIN");

    private final StaffRepository staffRepository;
    private final PasswordEncoder passwordEncoder;

    public StaffService(StaffRepository staffRepository, PasswordEncoder passwordEncoder) {
        this.staffRepository = staffRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public Staff get(UUID id) {
        return staffRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Staff member not found"));
    }

    @Transactional(readOnly = true)
    public List<Staff> list() {
        return staffRepository.findAllByOrderByFullName();
    }

    @Transactional
    public Staff create(String email, String fullName, String role, List<String> skills, String rawPassword) {
        String resolvedRole = normaliseRole(role);
        if (staffRepository.findByEmail(email).isPresent()) {
            throw new ConflictException("A staff member with that email already exists");
        }
        return staffRepository.save(new Staff(email, passwordEncoder.encode(rawPassword),
                fullName, resolvedRole, skills));
    }

    @Transactional
    public Staff update(UUID id, String role, Boolean active) {
        Staff staff = get(id);
        if (role != null) {
            staff.changeRole(normaliseRole(role));
        }
        if (active != null) {
            staff.setActive(active);
        }
        return staffRepository.save(staff);
    }

    /**
     * Create an ADMIN if one with this email doesn't exist; otherwise reset
     * the existing staffer's password, force role back to ADMIN, and ensure
     * they're active. Idempotent — safe to call on every boot.
     *
     * <p>Sole caller is {@code StudioAdminBootstrap}, which reads the email +
     * raw password from {@code STUDIO_BOOTSTRAP_ADMIN_*} env vars so we never
     * commit credentials. The platform owner sets those env vars on Render
     * once (kept in their password manager); restarting the service either
     * plants the account or resets it back to known-good. Clearing the env
     * vars turns it back into a no-op.
     */
    @Transactional
    public Staff upsertAdmin(String email, String fullName, String rawPassword) {
        return staffRepository.findByEmail(email)
                .map(existing -> {
                    existing.resetPasswordHash(passwordEncoder.encode(rawPassword));
                    existing.changeRole("ADMIN");
                    existing.setActive(true);
                    return staffRepository.save(existing);
                })
                .orElseGet(() -> staffRepository.save(new Staff(
                        email, passwordEncoder.encode(rawPassword),
                        fullName, "ADMIN", List.of())));
    }

    private String normaliseRole(String role) {
        String normalised = role == null ? "" : role.trim().toUpperCase();
        if (!ROLES.contains(normalised)) {
            throw new IllegalArgumentException("Invalid staff role: " + role);
        }
        return normalised;
    }
}
