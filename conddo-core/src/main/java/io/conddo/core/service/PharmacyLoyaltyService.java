package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.Customer;
import io.conddo.core.domain.PharmacyCustomerWallet;
import io.conddo.core.domain.PharmacyLoyaltyConfig;
import io.conddo.core.domain.PharmacyWalletTransaction;
import io.conddo.core.repository.CustomerRepository;
import io.conddo.core.repository.PharmacyCustomerWalletRepository;
import io.conddo.core.repository.PharmacyLoyaltyConfigRepository;
import io.conddo.core.repository.PharmacyWalletTransactionRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pharmacy Roadmap Beta 1 — per-tenant cashback loyalty. Config +
 * wallet + ledger live in the same service so a single tx encloses
 * "wallet balance update + ledger row" — losing one without the other
 * would silently break the audit trail.
 */
@Service
public class PharmacyLoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(PharmacyLoyaltyService.class);

    private final PharmacyLoyaltyConfigRepository configRepository;
    private final PharmacyCustomerWalletRepository walletRepository;
    private final PharmacyWalletTransactionRepository transactionRepository;
    private final CustomerRepository customerRepository;
    private final TenantSession tenantSession;

    public PharmacyLoyaltyService(PharmacyLoyaltyConfigRepository configRepository,
                                  PharmacyCustomerWalletRepository walletRepository,
                                  PharmacyWalletTransactionRepository transactionRepository,
                                  CustomerRepository customerRepository,
                                  TenantSession tenantSession) {
        this.configRepository = configRepository;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.customerRepository = customerRepository;
        this.tenantSession = tenantSession;
    }

    // ----- config ------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<PharmacyLoyaltyConfig> getConfig() {
        tenantSession.bind();
        return configRepository.findByTenantId(TenantContext.require());
    }

    @Transactional
    public PharmacyLoyaltyConfig upsertConfig(BigDecimal cashbackRate, BigDecimal minRedemption,
                                              Boolean isActive) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        if (cashbackRate != null
                && (cashbackRate.signum() < 0 || cashbackRate.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("cashbackRate must be 0..100");
        }
        if (minRedemption != null && minRedemption.signum() < 0) {
            throw new IllegalArgumentException("minRedemption must be >= 0");
        }
        PharmacyLoyaltyConfig config = configRepository.findByTenantId(tenantId)
                .orElseGet(() -> new PharmacyLoyaltyConfig(tenantId));
        if (cashbackRate != null) {
            config.setCashbackRate(cashbackRate);
        }
        if (minRedemption != null) {
            config.setMinRedemption(minRedemption);
        }
        if (isActive != null) {
            config.setActive(isActive);
        }
        return configRepository.save(config);
    }

    // ----- wallets -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<WalletView> listWallets(String search) {
        tenantSession.bind();
        List<PharmacyCustomerWallet> wallets = walletRepository.findAllByOrderByBalanceDesc();
        List<WalletView> out = new ArrayList<>();
        for (PharmacyCustomerWallet w : wallets) {
            Customer customer = customerRepository.findById(w.getCustomerId()).orElse(null);
            if (search != null && !search.isBlank()) {
                String q = search.trim().toLowerCase();
                String name = customer == null ? "" : safe(customer.getFullName()).toLowerCase();
                String phone = customer == null ? "" : safe(customer.getPhone()).toLowerCase();
                if (!name.contains(q) && !phone.contains(q)) {
                    continue;
                }
            }
            out.add(toView(w, customer));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public WalletView getWallet(UUID customerId) {
        tenantSession.bind();
        PharmacyCustomerWallet wallet = walletRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
        Customer customer = customerRepository.findById(customerId).orElse(null);
        return toView(wallet, customer);
    }

    @Transactional(readOnly = true)
    public List<PharmacyWalletTransaction> listTransactions(UUID customerId) {
        tenantSession.bind();
        PharmacyCustomerWallet wallet = walletRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
        return transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
    }

    // ----- credit + redeem (called by listeners + checkout) ------------------

    /**
     * Credit cashback for an order on its DELIVERED transition. Calls
     * are idempotent on {@code orderId} — running the listener twice
     * for the same order writes one CASHBACK_EARNED row, not two.
     * Returns the transaction row if credited, or empty when there was
     * nothing to do (no config, inactive, no order total, etc.).
     */
    @Transactional
    public Optional<PharmacyWalletTransaction> creditCashback(UUID customerId, UUID orderId,
                                                              BigDecimal orderTotalNgn) {
        if (customerId == null || orderId == null || orderTotalNgn == null
                || orderTotalNgn.signum() <= 0) {
            return Optional.empty();
        }
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        PharmacyLoyaltyConfig config = configRepository.findByTenantId(tenantId).orElse(null);
        if (config == null || !config.isActive() || config.getCashbackRate().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal credit = orderTotalNgn.multiply(config.getCashbackRate())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (credit.signum() <= 0) {
            return Optional.empty();
        }
        PharmacyCustomerWallet wallet = walletRepository.findByCustomerId(customerId)
                .orElseGet(() -> walletRepository.save(
                        new PharmacyCustomerWallet(tenantId, customerId)));
        // Idempotency check — match on (wallet, CASHBACK_EARNED, this orderId).
        boolean alreadyCredited = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId())
                .stream()
                .anyMatch(t -> PharmacyWalletTransaction.CASHBACK_EARNED.equals(t.getTransactionType())
                        && orderId.equals(t.getReferenceId()));
        if (alreadyCredited) {
            return Optional.empty();
        }
        wallet.credit(credit);
        walletRepository.save(wallet);
        PharmacyWalletTransaction tx = transactionRepository.save(new PharmacyWalletTransaction(
                tenantId, wallet.getId(), PharmacyWalletTransaction.CASHBACK_EARNED,
                credit, orderId, "Cashback for order " + orderId));
        log.info("Cashback credited: tenant={} customer={} order={} amount={}",
                tenantId, customerId, orderId, credit);
        return Optional.of(tx);
    }

    /**
     * Public checkout redemption. Validates the amount against the
     * config's minRedemption + the wallet's balance; deducts and
     * writes a REDEMPTION ledger row. Throws on any rule violation
     * so the controller maps to 4xx via the existing handler.
     */
    @Transactional
    public PharmacyWalletTransaction redeem(UUID customerId, BigDecimal amount, UUID orderId) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("redemption amount must be > 0");
        }
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        PharmacyLoyaltyConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Cashback is not configured"));
        if (!config.isActive()) {
            throw new IllegalArgumentException("Cashback is currently disabled");
        }
        if (amount.compareTo(config.getMinRedemption()) < 0) {
            throw new IllegalArgumentException(
                    "Redemption is below the minimum (" + config.getMinRedemption() + ")");
        }
        PharmacyCustomerWallet wallet = walletRepository.findByCustomerId(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer has no wallet yet"));
        wallet.debit(amount);   // throws when balance is insufficient
        walletRepository.save(wallet);
        PharmacyWalletTransaction tx = transactionRepository.save(new PharmacyWalletTransaction(
                tenantId, wallet.getId(), PharmacyWalletTransaction.REDEMPTION,
                amount.negate(), orderId,
                orderId == null ? "Cashback redemption" : "Redeemed against order " + orderId));
        return tx;
    }

    // ----- DTO + helpers -----------------------------------------------------

    private static WalletView toView(PharmacyCustomerWallet w, Customer customer) {
        return new WalletView(w.getCustomerId(),
                customer == null ? null : customer.getFullName(),
                customer == null ? null : customer.getPhone(),
                w.getBalance(), w.getTotalEarned(), w.getTotalRedeemed(),
                w.getUpdatedAt() == null ? null : w.getUpdatedAt().toString());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public record WalletView(UUID customerId, String customerName, String customerPhone,
                             BigDecimal balance, BigDecimal totalEarned, BigDecimal totalRedeemed,
                             String updatedAt) {
    }
}
