package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.PharmacyEmr;
import io.conddo.core.domain.PharmacyEmrDocument;
import io.conddo.core.domain.PharmacyEmrNote;
import io.conddo.core.service.PharmacyEmrService;
import io.conddo.core.service.PharmacyEmrService.EmrInput;
import io.conddo.core.service.PharmacyEmrService.EmrView;
import io.conddo.core.storage.StorageException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pharmacy Roadmap Beta 4 — EMR surface. Feature-gated by
 * {@code emr_basic}. Reads are TENANT_ADMIN + STAFF + SUPER_ADMIN per
 * the FE handoff §4 mapping; writes are TENANT_ADMIN-only.
 *
 * <p>Notes are immutable — there is no PUT or DELETE on a single
 * note. Documents have a list + upload but no rewrite either; spec
 * requires append-only.
 */
@RestController
@RequestMapping("/api/v1/pharmacy/emr")
@PreAuthorize("@featureFlagGuard.requiresFlag('emr_basic') "
        + "and hasAnyRole('TENANT_ADMIN','STAFF','SUPER_ADMIN')")
public class PharmacyEmrController {

    private static final String WRITE = "@featureFlagGuard.requiresFlag('emr_basic') "
            + "and hasAnyRole('TENANT_ADMIN','SUPER_ADMIN')";

    private final PharmacyEmrService service;

    public PharmacyEmrController(PharmacyEmrService service) {
        this.service = service;
    }

    @GetMapping("/{customerId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable UUID customerId) {
        EmrView view = service.get(customerId);
        return ApiResponse.ok(toEmrWithNotesRow(view));
    }

    @PostMapping("/{customerId}")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @PathVariable UUID customerId,
            @Valid @RequestBody EmrInputRequest body) {
        PharmacyEmr emr = service.create(customerId, body.toServiceInput());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.ok(toEmrRow(emr)));
    }

    @PutMapping("/{customerId}")
    @PreAuthorize(WRITE)
    public ApiResponse<Map<String, Object>> update(@PathVariable UUID customerId,
                                                    @Valid @RequestBody EmrInputRequest body) {
        return ApiResponse.ok(toEmrRow(service.update(customerId, body.toServiceInput())));
    }

    @PostMapping("/{customerId}/notes")
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> addNote(
            @PathVariable UUID customerId,
            @Valid @RequestBody AddNoteRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        UUID createdBy = UUID.fromString(jwt.getSubject());
        PharmacyEmrNote saved = service.addNote(customerId, body.note(), body.noteType(), createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toNoteRow(saved)));
    }

    @GetMapping("/{customerId}/documents")
    public ApiResponse<List<Map<String, Object>>> listDocuments(@PathVariable UUID customerId) {
        return ApiResponse.ok(service.listDocuments(customerId).stream()
                .map(PharmacyEmrController::toDocumentRow).toList());
    }

    @PostMapping(value = "/{customerId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(WRITE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadDocument(
            @PathVariable UUID customerId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("docType") String docType,
            @RequestParam(required = false) String label,
            @AuthenticationPrincipal Jwt jwt) {
        UUID uploadedBy = UUID.fromString(jwt.getSubject());
        try {
            PharmacyEmrDocument saved = service.addDocument(customerId,
                    file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), file.getInputStream(), docType, label, uploadedBy);
            return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toDocumentRow(saved)));
        } catch (IOException ex) {
            throw new StorageException("Could not read the uploaded file", ex);
        }
    }

    // ----- shapes ------------------------------------------------------------

    private static Map<String, Object> toEmrRow(PharmacyEmr emr) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerId", emr.getCustomerId());
        row.put("bloodGroup", emr.getBloodGroup());
        row.put("genotype", emr.getGenotype());
        row.put("heightCm", emr.getHeightCm());
        row.put("weightKg", emr.getWeightKg());
        row.put("allergies", emr.getAllergies());
        row.put("chronicConditions", emr.getChronicConditions());
        row.put("immunizations", emr.getImmunizations());
        row.put("createdAt", emr.getCreatedAt());
        row.put("updatedAt", emr.getUpdatedAt());
        return row;
    }

    private static Map<String, Object> toEmrWithNotesRow(EmrView view) {
        Map<String, Object> row = toEmrRow(view.emr());
        row.put("notes", view.notes().stream()
                .map(PharmacyEmrController::toNoteRow).toList());
        return row;
    }

    private static Map<String, Object> toNoteRow(PharmacyEmrNote note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", note.getId());
        row.put("note", note.getNote());
        row.put("noteType", note.getNoteType());
        Map<String, Object> by = new LinkedHashMap<>();
        by.put("id", note.getCreatedBy());
        row.put("createdBy", note.getCreatedBy() == null ? null : by);
        row.put("createdAt", note.getCreatedAt());
        return row;
    }

    private static Map<String, Object> toDocumentRow(PharmacyEmrDocument doc) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", doc.getId());
        row.put("label", doc.getLabel());
        row.put("fileUrl", doc.getFileUrl());
        row.put("docType", doc.getDocType());
        Map<String, Object> by = new LinkedHashMap<>();
        by.put("id", doc.getUploadedBy());
        row.put("uploadedBy", doc.getUploadedBy() == null ? null : by);
        row.put("uploadedAt", doc.getUploadedAt());
        return row;
    }

    // ----- request DTOs ------------------------------------------------------

    public record EmrInputRequest(String bloodGroup,
                                  String genotype,
                                  BigDecimal heightCm,
                                  BigDecimal weightKg,
                                  List<Map<String, Object>> allergies,
                                  List<Map<String, Object>> chronicConditions,
                                  List<Map<String, Object>> immunizations) {

        EmrInput toServiceInput() {
            return new EmrInput(bloodGroup, genotype, heightCm, weightKg,
                    allergies, chronicConditions, immunizations);
        }
    }

    public record AddNoteRequest(@NotBlank String note, @NotBlank String noteType) {
    }
}
