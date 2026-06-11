package io.conddo.core.service;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.auth.OpaqueToken;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.CustomerPasswordResetToken;
import io.conddo.core.notify.EmailSender;
import io.conddo.core.repository.CustomerPasswordResetTokenRepository;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.TenantRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Customer-side auth for the merchant's public pharmacy website
 * (PHARMACY_PUBLIC_API_SPEC §2). Distinct from the platform's user-side
 * auth: customer accounts are tenant-scoped (each merchant has their own
 * customer base) and identified by email per-tenant.
 *
 * <p>The tenant is bound on TenantContext before every call (the
 * PublicSiteInterceptor already did that based on the site key); RLS
 * scopes every customer query to the bound tenant. {@code findByEmail}
 * therefore returns at most one row per tenant.
 */
@Service
public class PublicCustomerAuthService {

    private static final Logger log = LoggerFactory.getLogger(PublicCustomerAuthService.class);

    private final CustomerRepository customerRepository;
    private final CustomerPasswordResetTokenRepository resetTokenRepository;
    private final TenantRepository tenantRepository;
    private final CustomerJwtService customerJwtService;
    private final PasswordHasher passwordHasher;
    private final EmailSender emailSender;
    private final TenantSession tenantSession;
    private final Clock clock;
    private final Duration resetTokenTtl;
    private final String appBaseUrl;

    public PublicCustomerAuthService(CustomerRepository customerRepository,
                                     CustomerPasswordResetTokenRepository resetTokenRepository,
                                     TenantRepository tenantRepository,
                                     CustomerJwtService customerJwtService,
                                     PasswordHasher passwordHasher,
                                     EmailSender emailSender,
                                     TenantSession tenantSession,
                                     Clock clock,
                                     @Value("${conddo.customer-password-reset.ttl:1h}") Duration resetTokenTtl,
                                     @Value("${conddo.app.base-url:https://app.conddo.io}") String appBaseUrl) {
        this.customerRepository = customerRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.tenantRepository = tenantRepository;
        this.customerJwtService = customerJwtService;
        this.passwordHasher = passwordHasher;
        this.emailSender = emailSender;
        this.tenantSession = tenantSession;
        this.clock = clock;
        this.resetTokenTtl = resetTokenTtl;
        this.appBaseUrl = appBaseUrl;
    }

    /**
     * Self-register a customer on the merchant's public website. Email is
     * unique per tenant — duplicate raises {@link EmailAlreadyRegisteredException}.
     */
    @Transactional
    public AuthResult register(String fullName, String email, String phone, String password) {
        validateRegisterInputs(fullName, email, password);
        tenantSession.bind();
        UUID tenantId = TenantContext.require();

        if (customerRepository.findByEmail(normalise(email)).isPresent()) {
            throw new EmailAlreadyRegisteredException(
                    "An account with this email already exists for this merchant.");
        }
        Customer customer = new Customer(tenantId, fullName.trim(), normalise(email),
                phone == null ? null : phone.trim(), null);
        customer.setPasswordHash(passwordHasher.hash(password));
        customer = customerRepository.save(customer);
        return new AuthResult(customer, customerJwtService.issue(customer.getId(), tenantId, clock));
    }

    /** Email + password sign-in. Returns a fresh JWT. */
    @Transactional(readOnly = true)
    public AuthResult login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new InvalidCustomerCredentialsException("Email and password are required.");
        }
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        Customer customer = customerRepository.findByEmail(normalise(email))
                .orElseThrow(() -> new InvalidCustomerCredentialsException(
                        "Invalid email or password."));
        if (customer.getPasswordHash() == null
                || !passwordHasher.matches(password, customer.getPasswordHash())) {
            throw new InvalidCustomerCredentialsException("Invalid email or password.");
        }
        return new AuthResult(customer, customerJwtService.issue(customer.getId(), tenantId, clock));
    }

    /** "Am I logged in?" — return the customer behind the supplied id. */
    @Transactional(readOnly = true)
    public Customer current(UUID customerId) {
        tenantSession.bind();
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
    }

    @Transactional(readOnly = true)
    public Optional<Customer> lookup(UUID customerId) {
        tenantSession.bind();
        return customerRepository.findById(customerId);
    }

    /**
     * Begin a password reset. Silent on unknown email (anti-enumeration).
     * When the email matches a customer on the bound tenant, mints an
     * opaque {@code selector.verifier} token and emails it. Site name +
     * the FE base URL are embedded so the FE can render a click-through
     * to {@code /reset?token=…}.
     */
    @Transactional
    public void forgotPassword(String email) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        Customer customer = customerRepository.findByEmail(normalise(email)).orElse(null);
        if (customer == null) {
            // Silent on unknown email — never tell the caller whether
            // an account exists for this address on this tenant.
            return;
        }
        String selector = OpaqueToken.randomBase64Url(OpaqueToken.SELECTOR_BYTES);
        String verifier = OpaqueToken.randomBase64Url(OpaqueToken.VERIFIER_BYTES);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(resetTokenTtl);
        resetTokenRepository.save(new CustomerPasswordResetToken(
                customer.getId(), tenantId, selector,
                passwordHasher.hash(verifier), expiresAt));
        String rawToken = selector + OpaqueToken.SEPARATOR + verifier;
        String storeName = tenantRepository.findById(tenantId)
                .map(t -> t.getName()).orElse("your pharmacy");
        String body = "Hi " + (customer.getFullName() == null ? "" : customer.getFullName())
                + ",\n\nWe received a request to reset your password at " + storeName + "."
                + "\n\nUse this token to reset it (valid for "
                + resetTokenTtl.toMinutes() + " minutes):\n\n" + rawToken
                + "\n\nIf you didn't request this, you can safely ignore this email.";
        try {
            emailSender.send(customer.getEmail(),
                    "Reset your password at " + storeName, body);
        } catch (RuntimeException ex) {
            // The token is already persisted; surface the email failure
            // to logs but keep the flow silent to the caller for
            // enumeration protection.
            log.warn("Customer password reset email failed for {}: {}",
                    customer.getEmail(), ex.getMessage());
        }
    }

    /**
     * Complete a password reset. Validates the {@code selector.verifier}
     * token, sets the new password, marks the token used. Throws
     * {@link InvalidPasswordResetTokenException} on any failure — same
     * shape for unknown selector, bad verifier, expired, and already-
     * used so the caller can't distinguish them.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        if (rawToken == null || newPassword == null || newPassword.length() < 8) {
            throw new InvalidPasswordResetTokenException();
        }
        int sep = rawToken.indexOf(OpaqueToken.SEPARATOR);
        if (sep <= 0 || sep == rawToken.length() - 1) {
            throw new InvalidPasswordResetTokenException();
        }
        String selector = rawToken.substring(0, sep);
        String verifier = rawToken.substring(sep + 1);

        CustomerPasswordResetToken token = resetTokenRepository.findBySelector(selector)
                .orElseThrow(InvalidPasswordResetTokenException::new);
        if (!passwordHasher.matches(verifier, token.getTokenHash())) {
            throw new InvalidPasswordResetTokenException();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!token.isUsable(now)) {
            throw new InvalidPasswordResetTokenException();
        }

        TenantContext.set(token.getTenantId());
        tenantSession.bind();
        Customer customer = customerRepository.findById(token.getCustomerId())
                .orElseThrow(InvalidPasswordResetTokenException::new);
        customer.setPasswordHash(passwordHasher.hash(newPassword));
        customerRepository.save(customer);
        token.markUsed(now);
        resetTokenRepository.save(token);
    }

    // ----- helpers -----------------------------------------------------------

    private void validateRegisterInputs(String fullName, String email, String password) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName is required");
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("Valid email is required");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
    }

    private static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    // ----- result + exceptions ----------------------------------------------

    public record AuthResult(Customer customer, String token) {
    }

    public static class EmailAlreadyRegisteredException extends RuntimeException {
        public EmailAlreadyRegisteredException(String msg) {
            super(msg);
        }
    }

    public static class InvalidCustomerCredentialsException extends RuntimeException {
        public InvalidCustomerCredentialsException(String msg) {
            super(msg);
        }
    }

    public static class InvalidPasswordResetTokenException extends RuntimeException {
        public InvalidPasswordResetTokenException() {
            super("Invalid or expired password reset token");
        }
    }
}
