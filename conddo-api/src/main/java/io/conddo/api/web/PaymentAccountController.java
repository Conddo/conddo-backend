package io.conddo.api.web;

import io.conddo.core.common.ApiResponse;
import io.conddo.core.domain.TenantPaymentAccount;
import io.conddo.core.service.PaymentAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing payment-account endpoints at {@code /api/v1/me/payments/account}.
 * Covers bank connect, KYC document uploads, and submission-for-review.
 * Admin approval / rejection lives on {@link AdminKycController}.
 */
@RestController
@RequestMapping("/api/v1/me/payments/account")
public class PaymentAccountController {

    private final PaymentAccountService service;

    public PaymentAccountController(PaymentAccountService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AccountView> get() {
        return ApiResponse.ok(AccountView.of(service.getOrCreate()));
    }

    @PutMapping("/bank")
    public ApiResponse<AccountView> updateBank(@Valid @RequestBody BankRequest req) {
        return ApiResponse.ok(AccountView.of(
                service.updateBankAccount(req.bankCode(), req.bankName(),
                        req.accountNumber(), req.accountName())));
    }

    @DeleteMapping("/bank")
    public ApiResponse<AccountView> clearBank() {
        return ApiResponse.ok(AccountView.of(service.clearBankAccount()));
    }

    @PutMapping("/kyc-docs")
    public ApiResponse<AccountView> updateKycDocs(@RequestBody KycDocsRequest req) {
        return ApiResponse.ok(AccountView.of(
                service.saveKycDocs(req.cacDocumentUrl(), req.directorIdUrl(),
                        req.utilityBillUrl(), req.businessAddress())));
    }

    @PostMapping("/submit")
    public ApiResponse<AccountView> submit() {
        return ApiResponse.ok(AccountView.of(service.submitForReview()));
    }

    public record BankRequest(
            @NotBlank String bankCode,
            @NotBlank String bankName,
            @NotBlank String accountNumber,
            @NotBlank String accountName
    ) {}

    public record KycDocsRequest(
            String cacDocumentUrl,
            String directorIdUrl,
            String utilityBillUrl,
            String businessAddress
    ) {}

    /** Tenant-facing view. Deliberately does NOT expose reviewerId. */
    public record AccountView(
            String bankCode, String bankName,
            String accountNumber, String accountName,
            boolean accountVerified,
            String kycStatus, String kycRejectionReason,
            String cacDocumentUrl, String directorIdUrl,
            String utilityBillUrl, String businessAddress,
            boolean paymentsEnabled
    ) {
        static AccountView of(TenantPaymentAccount a) {
            return new AccountView(
                    a.getBankCode(), a.getBankName(),
                    a.getAccountNumber(), a.getAccountName(),
                    a.getAccountVerifiedAt() != null,
                    a.getKycStatus(), a.getKycRejectionReason(),
                    a.getKycCacDocumentUrl(), a.getKycDirectorIdUrl(),
                    a.getKycUtilityBillUrl(), a.getKycBusinessAddress(),
                    a.isPaymentsEnabled()
            );
        }
    }
}
