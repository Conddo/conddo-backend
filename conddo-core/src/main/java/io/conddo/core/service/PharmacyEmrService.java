package io.conddo.core.service;

import io.conddo.core.audit.AuditService;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PharmacyEmr;
import io.conddo.core.domain.PharmacyEmrDocument;
import io.conddo.core.domain.PharmacyEmrNote;
import io.conddo.core.repository.PharmacyEmrDocumentRepository;
import io.conddo.core.repository.PharmacyEmrNoteRepository;
import io.conddo.core.repository.PharmacyEmrRepository;
import io.conddo.core.storage.ObjectStorage;
import io.conddo.core.storage.StorageException;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pharmacy Roadmap Beta 4 — EMR lifecycle. Demographics + notes +
 * documents in one service so the audit log (per-read, per-write,
 * per-download) stays consistent. Notes are immutable: there's a
 * {@link #addNote} but no update / delete equivalent.
 *
 * <p>Documents go to the same {@link ObjectStorage} the rest of the
 * media library uses. The roadmap calls for short-lived pre-signed
 * URLs; the current adapter (Cloudinary) returns permanent CDN
 * URLs, which is a known security gap noted at the
 * {@link #addDocument} site and tracked for a follow-up hardening
 * pass.
 */
@Service
public class PharmacyEmrService {

    private static final List<String> ALLOWED_NOTE_TYPES = List.of(
            PharmacyEmrNote.CLINICAL, PharmacyEmrNote.ALLERGY,
            PharmacyEmrNote.COUNSELLING, PharmacyEmrNote.REFERRAL);

    private static final List<String> ALLOWED_DOC_TYPES = List.of(
            PharmacyEmrDocument.LAB_RESULT, PharmacyEmrDocument.PRESCRIPTION,
            PharmacyEmrDocument.REFERRAL, PharmacyEmrDocument.IMAGING,
            PharmacyEmrDocument.OTHER);

    private final PharmacyEmrRepository emrRepository;
    private final PharmacyEmrNoteRepository noteRepository;
    private final PharmacyEmrDocumentRepository documentRepository;
    private final ObjectStorage storage;
    private final AuditService auditService;
    private final TenantSession tenantSession;

    public PharmacyEmrService(PharmacyEmrRepository emrRepository,
                              PharmacyEmrNoteRepository noteRepository,
                              PharmacyEmrDocumentRepository documentRepository,
                              ObjectStorage storage,
                              AuditService auditService,
                              TenantSession tenantSession) {
        this.emrRepository = emrRepository;
        this.noteRepository = noteRepository;
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.auditService = auditService;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public EmrView get(UUID customerId) {
        tenantSession.bind();
        PharmacyEmr emr = emrRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new NotFoundException("EMR not found"));
        List<PharmacyEmrNote> notes = noteRepository.findByEmrIdOrderByCreatedAtDesc(emr.getId());
        auditService.record("EMR_READ", "PHARMACY_EMR", emr.getId(), null, null);
        return new EmrView(emr, notes);
    }

    @Transactional
    public PharmacyEmr create(UUID customerId, EmrInput input) {
        tenantSession.bind();
        if (emrRepository.findByCustomerId(customerId).isPresent()) {
            throw new IllegalArgumentException("EMR already exists for this customer");
        }
        PharmacyEmr emr = applyInput(new PharmacyEmr(TenantContext.require(), customerId), input);
        emr = emrRepository.save(emr);
        auditService.record("EMR_CREATE", "PHARMACY_EMR", emr.getId(), null, snapshot(emr));
        return emr;
    }

    @Transactional
    public PharmacyEmr update(UUID customerId, EmrInput input) {
        tenantSession.bind();
        PharmacyEmr emr = emrRepository.findByCustomerId(customerId)
                .orElseGet(() -> new PharmacyEmr(TenantContext.require(), customerId));
        boolean isNew = emr.getId() == null;
        Map<String, Object> before = isNew ? null : snapshot(emr);
        applyInput(emr, input);
        emr = emrRepository.save(emr);
        auditService.record(isNew ? "EMR_CREATE" : "EMR_UPDATE",
                "PHARMACY_EMR", emr.getId(), before, snapshot(emr));
        return emr;
    }

    @Transactional
    public PharmacyEmrNote addNote(UUID customerId, String note, String noteType, UUID createdBy) {
        tenantSession.bind();
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("note is required");
        }
        String normalisedType = noteType == null ? PharmacyEmrNote.CLINICAL
                : noteType.trim().toUpperCase();
        if (!ALLOWED_NOTE_TYPES.contains(normalisedType)) {
            throw new IllegalArgumentException("noteType must be one of " + ALLOWED_NOTE_TYPES);
        }
        PharmacyEmr emr = emrRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new NotFoundException("EMR not found"));
        PharmacyEmrNote saved = noteRepository.save(new PharmacyEmrNote(
                TenantContext.require(), emr.getId(), note, normalisedType, createdBy));
        auditService.record("EMR_NOTE_ADD", "PHARMACY_EMR_NOTE", saved.getId(),
                null, Map.of("noteType", normalisedType));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PharmacyEmrDocument> listDocuments(UUID customerId) {
        tenantSession.bind();
        PharmacyEmr emr = emrRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new NotFoundException("EMR not found"));
        auditService.record("EMR_DOCUMENTS_LIST", "PHARMACY_EMR", emr.getId(), null, null);
        return documentRepository.findByEmrIdOrderByUploadedAtDesc(emr.getId());
    }

    /**
     * Upload a clinical document. The current ObjectStorage adapter
     * (Cloudinary) returns a permanent CDN URL; the spec calls for
     * pre-signed URLs with ≤1h expiry, which is a known gap to close
     * once we either swap to MinIO or layer Cloudinary's signed
     * delivery on top. For now we record the public URL and rely on
     * tenant-scoped public_id obscurity + the audit log to surface
     * access.
     */
    @Transactional
    public PharmacyEmrDocument addDocument(UUID customerId, String originalName,
                                            String contentType, long size, InputStream data,
                                            String docType, String label, UUID uploadedBy) {
        tenantSession.bind();
        String normalisedType = docType == null ? null : docType.trim().toUpperCase();
        if (normalisedType == null || !ALLOWED_DOC_TYPES.contains(normalisedType)) {
            throw new IllegalArgumentException("docType must be one of " + ALLOWED_DOC_TYPES);
        }
        if (contentType == null || !isAcceptableType(contentType)) {
            throw new IllegalArgumentException(
                    "Only PDF or image uploads are allowed for EMR documents");
        }
        if (size > 20L * 1024L * 1024L) {
            throw new IllegalArgumentException("File too large (max 20 MB)");
        }
        PharmacyEmr emr = emrRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new NotFoundException("EMR not found"));
        UUID tenantId = TenantContext.require();
        String key = "pharmacy/emr/" + tenantId + "/" + emr.getId() + "/" + UUID.randomUUID();
        ObjectStorage.Stored stored;
        try {
            stored = storage.put(key, contentType, size, data);
        } catch (StorageException ex) {
            throw ex;
        }
        PharmacyEmrDocument saved = documentRepository.save(new PharmacyEmrDocument(
                tenantId, emr.getId(), label, stored.url(), normalisedType, uploadedBy));
        auditService.record("EMR_DOCUMENT_UPLOAD", "PHARMACY_EMR_DOCUMENT", saved.getId(),
                null, Map.of("docType", normalisedType, "label", label == null ? "" : label));
        return saved;
    }

    // ----- helpers + records -------------------------------------------------

    private static boolean isAcceptableType(String contentType) {
        String lower = contentType.toLowerCase();
        return lower.startsWith("image/") || lower.equals("application/pdf");
    }

    private static PharmacyEmr applyInput(PharmacyEmr emr, EmrInput input) {
        if (input.bloodGroup != null) {
            emr.setBloodGroup(input.bloodGroup);
        }
        if (input.genotype != null) {
            emr.setGenotype(input.genotype);
        }
        if (input.heightCm != null) {
            emr.setHeightCm(input.heightCm);
        }
        if (input.weightKg != null) {
            emr.setWeightKg(input.weightKg);
        }
        if (input.allergies != null) {
            emr.setAllergies(input.allergies);
        }
        if (input.chronicConditions != null) {
            emr.setChronicConditions(input.chronicConditions);
        }
        if (input.immunizations != null) {
            emr.setImmunizations(input.immunizations);
        }
        return emr;
    }

    private static Map<String, Object> snapshot(PharmacyEmr emr) {
        return Map.of(
                "bloodGroup", emr.getBloodGroup() == null ? "" : emr.getBloodGroup(),
                "genotype", emr.getGenotype() == null ? "" : emr.getGenotype(),
                "heightCm", emr.getHeightCm() == null ? "" : emr.getHeightCm().toPlainString(),
                "weightKg", emr.getWeightKg() == null ? "" : emr.getWeightKg().toPlainString());
    }

    public record EmrInput(String bloodGroup, String genotype,
                           BigDecimal heightCm, BigDecimal weightKg,
                           List<Map<String, Object>> allergies,
                           List<Map<String, Object>> chronicConditions,
                           List<Map<String, Object>> immunizations) {
    }

    public record EmrView(PharmacyEmr emr, List<PharmacyEmrNote> notes) {
    }
}
