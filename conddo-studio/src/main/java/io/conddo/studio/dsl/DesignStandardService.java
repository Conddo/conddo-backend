package io.conddo.studio.dsl;

import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.DesignStandard;
import io.conddo.studio.repository.DesignStandardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin CRUD over the Design Standard Library (Infrastructure §8). Wraps
 * {@link DesignStandardRepository} with validation and a vertical-aware lookup
 * used by the AI assistant (a follow-up slice).
 *
 * <p>Vertical {@code null} means "applies to every vertical" — the
 * {@link #forVertical(String, String)} lookup always includes globals alongside
 * the requested vertical's specifics.
 */
@Service
public class DesignStandardService {

    /** Accepted {@code kind} values — kept in sync with the V3 CHECK constraint. */
    static final Set<String> KINDS = Set.of("PALETTE", "LAYOUT", "COPY_PATTERN", "TYPOGRAPHY");

    private final DesignStandardRepository repository;

    public DesignStandardService(DesignStandardRepository repository) {
        this.repository = repository;
    }

    // ----- reads --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DesignStandard> list() {
        return repository.findAllByOrderByKindAscVerticalAscNameAsc();
    }

    @Transactional(readOnly = true)
    public List<DesignStandard> byKind(String kind) {
        return repository.findByKindOrderByVerticalAscNameAsc(normaliseKind(kind));
    }

    @Transactional(readOnly = true)
    public DesignStandard get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Design standard not found: " + id));
    }

    /**
     * Active standards for a vertical — globals (vertical=null) always included.
     * Used by the AI assistant to ground prompts. Empty list when neither exist.
     */
    @Transactional(readOnly = true)
    public List<DesignStandard> forVertical(String kind, String vertical) {
        List<String> verticals = new ArrayList<>();
        verticals.add(null);
        if (vertical != null && !vertical.isBlank()) {
            verticals.add(vertical);
        }
        return repository.findByKindAndActiveTrueAndVerticalIn(normaliseKind(kind), verticals);
    }

    // ----- writes -------------------------------------------------------------

    @Transactional
    public DesignStandard create(String vertical, String kind, String name, String description,
                                 Map<String, Object> content) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return repository.save(new DesignStandard(blankToNull(vertical), normaliseKind(kind),
                name.trim(), description, content));
    }

    @Transactional
    public DesignStandard update(UUID id, String vertical, String name, String description,
                                 Map<String, Object> content, Boolean active) {
        DesignStandard standard = get(id);
        // Vertical of "" is treated as null (global); a non-blank string sets a specific vertical.
        if (vertical != null) {
            standard.setVertical(blankToNull(vertical));
        }
        standard.rename(name);
        if (description != null) {
            standard.describe(description);
        }
        if (content != null) {
            standard.setContent(content);
        }
        if (active != null) {
            standard.setActive(active);
        }
        return repository.save(standard);
    }

    /** Soft-delete — flips {@code active=false}. We never hard-delete so DSL history stays auditable. */
    @Transactional
    public void disable(UUID id) {
        DesignStandard standard = get(id);
        standard.setActive(false);
        repository.save(standard);
    }

    // ----- helpers ------------------------------------------------------------

    private static String normaliseKind(String kind) {
        String normalised = kind == null ? "" : kind.trim().toUpperCase();
        if (!KINDS.contains(normalised)) {
            throw new IllegalArgumentException("kind must be one of " + KINDS + ", got: " + kind);
        }
        return normalised;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
