package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.CustomerPrescription;
import io.conddo.core.repository.CustomerPrescriptionRepository;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Customer-side prescription upload (PHARMACY_PUBLIC_API_SPEC §6). The
 * customer uploads the prescription image / PDF (via the upload endpoint
 * — not in this slice), then POSTs the resulting URL here. The dashboard
 * pharmacist reviews via the existing {@link PharmacyDashboardService}.
 *
 * <p>Both the customer-submit + customer-list endpoints live on the same
 * {@code customer_prescriptions} table (shipped d1a5f43); the pharmacist
 * dashboard endpoints are unaffected.
 */
@Service
public class PublicCustomerPrescriptionService {

    private final CustomerPrescriptionRepository prescriptionRepository;
    private final CustomerRepository customerRepository;
    private final TenantSession tenantSession;

    public PublicCustomerPrescriptionService(CustomerPrescriptionRepository prescriptionRepository,
                                             CustomerRepository customerRepository,
                                             TenantSession tenantSession) {
        this.prescriptionRepository = prescriptionRepository;
        this.customerRepository = customerRepository;
        this.tenantSession = tenantSession;
    }

    @Transactional
    public CustomerPrescription submit(UUID customerId, String fileUrl, String patientName,
                                       String prescriberName, String notes) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new IllegalArgumentException("fileUrl is required");
        }
        if (patientName == null || patientName.isBlank()) {
            throw new IllegalArgumentException("patientName is required");
        }
        if (prescriberName == null || prescriberName.isBlank()) {
            throw new IllegalArgumentException("prescriberName is required");
        }
        tenantSession.bind();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        return prescriptionRepository.save(new CustomerPrescription(
                customer.getTenantId(), customer.getId(), customer.getFullName(),
                customer.getPhone(), fileUrl.trim(),
                patientName.trim(), prescriberName.trim(), notes));
    }

    /** A customer's own submissions, newest first. */
    @Transactional(readOnly = true)
    public List<CustomerPrescription> listMine(UUID customerId) {
        tenantSession.bind();
        // RLS already scopes to the tenant; filter to the calling customer.
        return prescriptionRepository.findAllByOrderByStatusAscSubmittedAtDesc().stream()
                .filter(p -> customerId.equals(p.getCustomerId()))
                .collect(Collectors.toList());
    }
}
