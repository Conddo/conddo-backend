package io.conddo.api.publicapi;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.domain.CustomerPrescription;
import io.conddo.core.service.PublicCustomerPrescriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Customer-side prescription upload (PHARMACY_PUBLIC_API_SPEC §6).
 * The image upload itself goes to the upload endpoint (Slice 3); this
 * controller takes the resulting URL and creates the review queue row
 * the pharmacist's dashboard reads from.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/pharmacy/prescriptions")
public class PublicCustomerPrescriptionController {

    private final PublicCustomerPrescriptionService service;
    private final CustomerJwtService customerJwtService;

    public PublicCustomerPrescriptionController(PublicCustomerPrescriptionService service,
                                                CustomerJwtService customerJwtService) {
        this.service = service;
        this.customerJwtService = customerJwtService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody SubmitRequest body,
                                                      HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        CustomerPrescription created = service.submit(customerId, body.fileUrl(),
                body.patientName(), body.prescriberName(), body.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "prescription", toShort(created)));
    }

    @GetMapping
    public Map<String, Object> mine(HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        return Map.of("prescriptions", service.listMine(customerId).stream()
                .map(PublicCustomerPrescriptionController::toFull)
                .toList());
    }

    private static Map<String, Object> toShort(CustomerPrescription p) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("status", p.getStatus());
        m.put("submittedAt", p.getSubmittedAt());
        return m;
    }

    private static Map<String, Object> toFull(CustomerPrescription p) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("fileUrl", p.getFileUrl());
        m.put("patientName", p.getPatientName());
        m.put("prescriberName", p.getPrescriberName());
        m.put("notes", p.getNotes());
        m.put("status", p.getStatus());
        m.put("reviewNote", p.getReviewNote());
        m.put("submittedAt", p.getSubmittedAt());
        m.put("reviewedAt", p.getReviewedAt());
        m.put("orderId", p.getOrderId());
        return m;
    }

    public record SubmitRequest(
            @NotBlank String fileUrl,
            @NotBlank String patientName,
            @NotBlank String prescriberName,
            String notes) {
    }
}
