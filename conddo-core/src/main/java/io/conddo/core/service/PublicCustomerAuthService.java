package io.conddo.core.service;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.auth.PasswordHasher;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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

    private final CustomerRepository customerRepository;
    private final CustomerJwtService customerJwtService;
    private final PasswordHasher passwordHasher;
    private final TenantSession tenantSession;
    private final Clock clock;

    public PublicCustomerAuthService(CustomerRepository customerRepository,
                                     CustomerJwtService customerJwtService,
                                     PasswordHasher passwordHasher,
                                     TenantSession tenantSession,
                                     Clock clock) {
        this.customerRepository = customerRepository;
        this.customerJwtService = customerJwtService;
        this.passwordHasher = passwordHasher;
        this.tenantSession = tenantSession;
        this.clock = clock;
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
}
