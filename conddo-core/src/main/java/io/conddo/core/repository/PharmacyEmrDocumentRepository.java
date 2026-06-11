package io.conddo.core.repository;

import io.conddo.core.domain.PharmacyEmrDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PharmacyEmrDocumentRepository extends JpaRepository<PharmacyEmrDocument, UUID> {

    List<PharmacyEmrDocument> findByEmrIdOrderByUploadedAtDesc(UUID emrId);
}
