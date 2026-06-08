package io.conddo.api.publicapi;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.domain.CustomerAddress;
import io.conddo.core.service.PharmacyDeliveryFeeService;
import io.conddo.core.service.PublicCustomerAddressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Customer address book (PHARMACY_PUBLIC_API_SPEC §7). All endpoints
 * require a customer JWT (verified via {@link PublicCustomerAuth}).
 * Site-key auth (via PublicSiteInterceptor) has already bound the tenant.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/customer/addresses")
public class PublicCustomerAddressController {

    private final PublicCustomerAddressService service;
    private final PharmacyDeliveryFeeService deliveryFeeService;
    private final CustomerJwtService customerJwtService;

    public PublicCustomerAddressController(PublicCustomerAddressService service,
                                           PharmacyDeliveryFeeService deliveryFeeService,
                                           CustomerJwtService customerJwtService) {
        this.service = service;
        this.deliveryFeeService = deliveryFeeService;
        this.customerJwtService = customerJwtService;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        return Map.of("addresses", service.list(customerId).stream()
                .map(this::toResponse)
                .toList());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateAddressRequest body,
                                                      HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        CustomerAddress created = service.create(customerId, body.label(), body.street(),
                body.city(), body.state(), body.landmark(),
                Boolean.TRUE.equals(body.isDefault()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @DeleteMapping("/{addressId}")
    public Map<String, Object> delete(@PathVariable UUID addressId, HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        service.delete(customerId, addressId);
        return Map.of("success", true);
    }

    private Map<String, Object> toResponse(CustomerAddress a) {
        PharmacyDeliveryFeeService.Quote quote = deliveryFeeService.quote(a.getState());
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("label", a.getLabel());
        m.put("street", a.getStreet());
        m.put("city", a.getCity());
        m.put("state", a.getState());
        m.put("landmark", a.getLandmark());
        m.put("isDefault", a.isDefaultAddress());
        m.put("deliveryFee", quote.fee());
        m.put("deliveryEstimate", quote.estimate());
        return m;
    }

    public record CreateAddressRequest(
            String label,
            @NotBlank String street,
            String city,
            @NotBlank String state,
            String landmark,
            Boolean isDefault) {
    }
}
