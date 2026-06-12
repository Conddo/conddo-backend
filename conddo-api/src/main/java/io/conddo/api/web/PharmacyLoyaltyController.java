package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.PharmacyLoyaltyConfig;
import io.conddo.core.domain.PharmacyWalletTransaction;
import io.conddo.core.service.PharmacyLoyaltyService;
import io.conddo.core.service.PharmacyLoyaltyService.WalletView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pharmacy Roadmap Beta 1 — tenant-side cashback loyalty surface.
 * Feature-gated by {@code cashback_loyalty}.
 */
@RestController
@RequestMapping("/api/v1/pharmacy/loyalty")
@PreAuthorize("@featureFlagGuard.requiresFlag('cashback_loyalty') "
        + "and @staffAccess.canRead('loyalty')")
public class PharmacyLoyaltyController {

    private final PharmacyLoyaltyService service;

    public PharmacyLoyaltyController(PharmacyLoyaltyService service) {
        this.service = service;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        PharmacyLoyaltyConfig config = service.getConfig()
                .orElseThrow(() -> new NotFoundException("Cashback isn't configured yet"));
        return ApiResponse.ok(toConfigRow(config));
    }

    @PutMapping("/config")
    @PreAuthorize("@featureFlagGuard.requiresFlag('cashback_loyalty') "
            + "and @staffAccess.canWrite('loyalty')")
    public ApiResponse<Map<String, Object>> upsertConfig(
            @Valid @RequestBody ConfigInput body) {
        PharmacyLoyaltyConfig saved = service.upsertConfig(
                body.cashbackRate(), body.minRedemption(), body.isActive());
        return ApiResponse.ok(toConfigRow(saved));
    }

    @GetMapping("/wallets")
    public ApiResponse<List<Map<String, Object>>> wallets(
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(service.listWallets(search).stream()
                .map(PharmacyLoyaltyController::toWalletRow).toList());
    }

    @GetMapping("/wallets/{customerId}")
    public ApiResponse<Map<String, Object>> wallet(@PathVariable UUID customerId) {
        return ApiResponse.ok(toWalletRow(service.getWallet(customerId)));
    }

    @GetMapping("/wallets/{customerId}/transactions")
    public ApiResponse<List<Map<String, Object>>> transactions(@PathVariable UUID customerId) {
        return ApiResponse.ok(service.listTransactions(customerId).stream()
                .map(PharmacyLoyaltyController::toTxRow).toList());
    }

    // ----- shapes ------------------------------------------------------------

    private static Map<String, Object> toConfigRow(PharmacyLoyaltyConfig c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cashbackRate", c.getCashbackRate());
        row.put("minRedemption", c.getMinRedemption());
        row.put("isActive", c.isActive());
        row.put("updatedAt", c.getUpdatedAt());
        return row;
    }

    private static Map<String, Object> toWalletRow(WalletView v) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerId", v.customerId());
        row.put("customerName", v.customerName());
        row.put("customerPhone", v.customerPhone());
        row.put("balance", v.balance());
        row.put("totalEarned", v.totalEarned());
        row.put("totalRedeemed", v.totalRedeemed());
        row.put("updatedAt", v.updatedAt());
        return row;
    }

    private static Map<String, Object> toTxRow(PharmacyWalletTransaction t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", t.getId());
        row.put("transactionType", t.getTransactionType());
        row.put("amount", t.getAmount());
        row.put("referenceId", t.getReferenceId());
        row.put("note", t.getNote());
        row.put("createdAt", t.getCreatedAt());
        return row;
    }

    public record ConfigInput(BigDecimal cashbackRate,
                              BigDecimal minRedemption,
                              Boolean isActive) {
    }
}
