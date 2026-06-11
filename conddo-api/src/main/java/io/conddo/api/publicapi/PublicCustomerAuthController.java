package io.conddo.api.publicapi;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.domain.Customer;
import io.conddo.core.service.PublicCustomerAuthService;
import io.conddo.core.service.PublicCustomerAuthService.AuthResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Customer-side auth for the merchant's public pharmacy website
 * (PHARMACY_PUBLIC_API_SPEC §2). The PublicSiteInterceptor has already
 * verified the {@code X-Conddo-Site-Key} and bound the tenant by the
 * time these endpoints run; the customer JWT is verified inline here
 * (it's a different trust domain — HMAC, not the platform's RSA pair).
 *
 * <p>Wire shape locks in {@code conddo-app/lib/api/pharmacyPublic.ts}
 * and Seb&Bayor's curl smoke tests in
 * STUDIO_DEV_ONBOARDING_SEB_BAYOR.md §2.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}/auth")
public class PublicCustomerAuthController {

    private final PublicCustomerAuthService service;
    private final CustomerJwtService customerJwtService;

    public PublicCustomerAuthController(PublicCustomerAuthService service,
                                        CustomerJwtService customerJwtService) {
        this.service = service;
        this.customerJwtService = customerJwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest body) {
        AuthResult result = service.register(body.fullName(), body.email(),
                body.phone(), body.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "token", result.token(),
                "customer", toPublic(result.customer())));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body) {
        AuthResult result = service.login(body.email(), body.password());
        return Map.of(
                "success", true,
                "token", result.token(),
                "customer", toPublic(result.customer()));
    }

    /**
     * PHARMACY_PUBLIC_API_SPEC §2 — kicks off the password reset for a
     * customer at the bound tenant. Always 200, never reveals whether
     * the email matches a row (anti-enumeration).
     */
    @PostMapping("/forgot-password")
    public Map<String, Object> forgotPassword(@Valid @RequestBody ForgotPasswordRequest body) {
        service.forgotPassword(body.email());
        return Map.of("success", true,
                "message", "If an account exists for that email, a reset link has been sent.");
    }

    /**
     * PHARMACY_PUBLIC_API_SPEC §2 — completes the reset using the
     * opaque token delivered via email. Invalid / expired / used tokens
     * all surface as 400 INVALID_RESET_TOKEN with no further detail.
     */
    @PostMapping("/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        service.resetPassword(body.token(), body.newPassword());
        return Map.of("success", true,
                "message", "Password updated. You can now sign in with the new password.");
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Optional<UUID> customerId = customerIdFromAuthHeader(request);
        if (customerId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "UNAUTHENTICATED",
                    "message", "Missing or invalid customer JWT"));
        }
        return ResponseEntity.ok(Map.of(
                "customer", toMe(service.current(customerId.get()))));
    }

    // ----- helpers ----------------------------------------------------------

    private Optional<UUID> customerIdFromAuthHeader(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = header.substring("Bearer ".length()).trim();
        return customerJwtService.verify(token).map(CustomerJwtService::customerId);
    }

    private static Map<String, Object> toPublic(Customer c) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("fullName", c.getFullName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        return m;
    }

    private static Map<String, Object> toMe(Customer c) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("fullName", c.getFullName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone());
        OffsetDateTime createdAt = c.getCreatedAt();
        if (createdAt != null) {
            m.put("createdAt", createdAt);
        }
        return m;
    }

    public record RegisterRequest(
            @NotBlank String fullName,
            @NotBlank @Email String email,
            String phone,
            @NotBlank @Size(min = 8) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ResetPasswordRequest(@NotBlank String token,
                                        @NotBlank @Size(min = 8) String newPassword) {
    }
}
