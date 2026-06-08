package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.CustomerAddress;
import io.conddo.core.repository.CustomerAddressRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Customer-side delivery address book (PHARMACY_PUBLIC_API_SPEC §7).
 * RLS scopes to the bound tenant; the service further filters by
 * {@code customerId} so one tenant's customers can't see each other's
 * addresses.
 */
@Service
public class PublicCustomerAddressService {

    private final CustomerAddressRepository repository;
    private final TenantSession tenantSession;

    public PublicCustomerAddressService(CustomerAddressRepository repository,
                                        TenantSession tenantSession) {
        this.repository = repository;
        this.tenantSession = tenantSession;
    }

    @Transactional(readOnly = true)
    public List<CustomerAddress> list(UUID customerId) {
        tenantSession.bind();
        return repository.findByCustomerIdOrderByDefaultAddressDescCreatedAtDesc(customerId);
    }

    /**
     * Create a new address. If {@code isDefault=true} or this is the
     * customer's first address, the new row becomes the default and any
     * existing default is cleared.
     */
    @Transactional
    public CustomerAddress create(UUID customerId, String label, String street, String city,
                                  String state, String landmark, boolean isDefault) {
        tenantSession.bind();
        if (street == null || street.isBlank()) {
            throw new IllegalArgumentException("street is required");
        }
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state is required");
        }
        boolean makeDefault = isDefault || !repository.existsByCustomerId(customerId);
        if (makeDefault) {
            clearOtherDefaults(customerId);
        }
        return repository.save(new CustomerAddress(TenantContext.require(), customerId,
                label, street.trim(), city, state.trim(),
                landmark, makeDefault));
    }

    @Transactional
    public void delete(UUID customerId, UUID addressId) {
        tenantSession.bind();
        CustomerAddress addr = repository.findById(addressId)
                .orElseThrow(() -> new NotFoundException("Address not found"));
        if (!addr.getCustomerId().equals(customerId)) {
            throw new NotFoundException("Address not found");
        }
        repository.delete(addr);
    }

    private void clearOtherDefaults(UUID customerId) {
        for (CustomerAddress existing :
                repository.findByCustomerIdOrderByDefaultAddressDescCreatedAtDesc(customerId)) {
            if (existing.isDefaultAddress()) {
                existing.setDefaultAddress(false);
                repository.save(existing);
            }
        }
    }
}
