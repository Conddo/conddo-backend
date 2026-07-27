package io.conddo.api.publicapi;

import io.conddo.core.domain.Consultation;
import io.conddo.core.service.PharmacyDashboardService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/{slug}/pharmacy/consultations")
public class PublicPharmacyConsultationController {

    private final PharmacyDashboardService service;

    public PublicPharmacyConsultationController(PharmacyDashboardService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody ConsultationRequest req) {
        Consultation created = service.createConsultation(
                req.customerName(),
                req.whatsappNumber(),
                req.topic()
        );

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", created.getId());
        resp.put("message", "Consultation request received. A pharmacist will contact you shortly.");
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    public record ConsultationRequest(
            @NotBlank String customerName,
            @NotBlank String whatsappNumber,
            @NotBlank String topic
    ) {
    }
}
