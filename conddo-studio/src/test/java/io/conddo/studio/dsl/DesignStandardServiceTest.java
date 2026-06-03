package io.conddo.studio.dsl;

import io.conddo.studio.common.NotFoundException;
import io.conddo.studio.domain.DesignStandard;
import io.conddo.studio.repository.DesignStandardRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Design Standard Library CRUD: kind validation, vertical null-handling,
 * vertical lookup includes globals, soft-delete keeps the row but flips
 * active, and update is partial.
 */
class DesignStandardServiceTest {

    private final DesignStandardRepository repository = mock(DesignStandardRepository.class);
    private final DesignStandardService service = new DesignStandardService(repository);

    @Test
    void createPersistsAndReturnsTheStandard() {
        when(repository.save(any(DesignStandard.class))).thenAnswer(inv -> inv.getArgument(0));

        DesignStandard saved = service.create("pharmacy", "palette", "Calming greens", "Pharmacy go-to",
                Map.of("primary", "#22C55E", "background", "#FFFFFF"));

        assertEquals("pharmacy", saved.getVertical());
        assertEquals("PALETTE", saved.getKind());   // normalised to upper
        assertEquals("Calming greens", saved.getName());
        verify(repository).save(any(DesignStandard.class));
    }

    @Test
    void createTreatsBlankVerticalAsGlobal() {
        when(repository.save(any(DesignStandard.class))).thenAnswer(inv -> inv.getArgument(0));
        DesignStandard saved = service.create("", "TYPOGRAPHY", "Inter + Geist Mono", null, null);
        assertNull(saved.getVertical(), "blank vertical should be normalised to null (= global)");
    }

    @Test
    void createRejectsUnknownKind() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
                "pharmacy", "INVALID", "x", null, null));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> service.create(
                "pharmacy", "PALETTE", "  ", null, null));
    }

    @Test
    void forVerticalAlwaysIncludesGlobals() {
        when(repository.findByKindAndActiveTrueAndVerticalIn(eq("PALETTE"), any()))
                .thenReturn(List.of());

        service.forVertical("palette", "pharmacy");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> verticals = ArgumentCaptor.forClass(List.class);
        verify(repository).findByKindAndActiveTrueAndVerticalIn(eq("PALETTE"), verticals.capture());
        // Includes null (globals) PLUS the requested vertical.
        assertTrue(verticals.getValue().contains(null), "globals should be requested");
        assertTrue(verticals.getValue().contains("pharmacy"));
    }

    @Test
    void forVerticalWithNoVerticalQueriesGlobalsOnly() {
        when(repository.findByKindAndActiveTrueAndVerticalIn(eq("LAYOUT"), any()))
                .thenReturn(List.of());

        service.forVertical("LAYOUT", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> verticals = ArgumentCaptor.forClass(List.class);
        verify(repository).findByKindAndActiveTrueAndVerticalIn(eq("LAYOUT"), verticals.capture());
        assertEquals(Arrays.asList((String) null), verticals.getValue());
    }

    @Test
    void updateIsPartialAndKeepsExistingValues() {
        UUID id = UUID.randomUUID();
        DesignStandard existing = new DesignStandard("pharmacy", "PALETTE", "Greens", "Original desc",
                Map.of("primary", "#22C55E"));
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        // Only rename + new content.
        service.update(id, null, "Calming greens (revised)", null, Map.of("primary", "#16A34A"), null);

        assertEquals("Calming greens (revised)", existing.getName());
        assertEquals("pharmacy", existing.getVertical());   // not touched
        assertEquals("Original desc", existing.getDescription());   // not touched
        assertEquals("#16A34A", existing.getContent().get("primary"));
        assertTrue(existing.isActive());   // not touched
    }

    @Test
    void updateOnUnknownStandardIs404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.update(id, null, "x", null, null, null));
    }

    @Test
    void disableSoftDeletes() {
        UUID id = UUID.randomUUID();
        DesignStandard existing = new DesignStandard(null, "TYPOGRAPHY", "Inter + Geist Mono", null, Map.of());
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        service.disable(id);

        assertFalse(existing.isActive());
        verify(repository).save(existing);
    }

    @Test
    void disableOnUnknownStandardIs404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.disable(id));
    }

    @Test
    void byKindNormalisesToUpper() {
        when(repository.findByKindOrderByVerticalAscNameAsc("COPY_PATTERN")).thenReturn(List.of());
        service.byKind("copy_pattern");
        verify(repository).findByKindOrderByVerticalAscNameAsc("COPY_PATTERN");
    }
}
