package io.conddo.api.web.dto;

import io.conddo.core.domain.Customer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String fullName,
        String email,
        String phone,
        String notes,
        BigDecimal totalSpent,
        OffsetDateTime createdAt
) {
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(
                c.getId(), c.getFullName(), c.getEmail(), c.getPhone(),
                c.getNotes(), c.getTotalSpent(), c.getCreatedAt());
    }
}
