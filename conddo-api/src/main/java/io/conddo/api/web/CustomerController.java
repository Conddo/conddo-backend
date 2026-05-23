package io.conddo.api.web;

import io.conddo.api.web.dto.CreateCustomerRequest;
import io.conddo.api.web.dto.CustomerResponse;
import io.conddo.core.common.ApiResponse;
import io.conddo.core.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tenant-scoped CRM endpoints (PRD §13.1). The tenant is taken from the
 * X-Tenant-Id header (resolved by TenantFilter) and enforced by RLS — these
 * methods never see another tenant's data.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CustomerResponse>> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse body = CustomerResponse.from(
                customerService.create(request.fullName(), request.email(), request.phone(), request.notes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @GetMapping
    public ApiResponse<List<CustomerResponse>> list() {
        List<CustomerResponse> items = customerService.findAll().stream()
                .map(CustomerResponse::from)
                .toList();
        return ApiResponse.ok(items, ApiResponse.Meta.total(items.size()));
    }
}
