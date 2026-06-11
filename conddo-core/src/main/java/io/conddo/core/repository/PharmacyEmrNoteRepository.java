package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyEmrNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PharmacyEmrNoteRepository extends JpaRepository<PharmacyEmrNote, UUID> {

    List<PharmacyEmrNote> findByEmrIdOrderByCreatedAtDesc(UUID emrId);
}
