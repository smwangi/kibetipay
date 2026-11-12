package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.LendingQueries;
import com.samwan.kibetipay.lending.LendingQueries.LoanDetail;
import com.samwan.kibetipay.lending.LendingQueries.LoanSummary;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.wallet.WalletApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Borrower-facing endpoints. */
@RestController
class BorrowerLoanController {

    private final LendingQueries queries;
    private final RepaymentService repayments;
    private final ProductCatalog products;
    private final WalletApi wallets;

    BorrowerLoanController(LendingQueries queries, RepaymentService repayments, ProductCatalog products,
                           WalletApi wallets) {
        this.queries = queries;
        this.repayments = repayments;
        this.products = products;
        this.wallets = wallets;
    }

    @GetMapping("/api/loans/products")
    List<Product> products() {
        return products.active();
    }

    @GetMapping("/api/loans")
    List<LoanSummary> mine() {
        return queries.loansOf(CurrentUser.id());
    }

    @GetMapping("/api/loans/{id}")
    LoanDetail get(@PathVariable UUID id) {
        return queries.loan(id, CurrentUser.id())
                .orElseThrow(() -> new DomainException.NotFound("loan_not_found", "No loan " + id));
    }

    /** Repay from the wallet (top it up via M-Pesa deposit first). */
    @PostMapping("/api/loans/{id}/repayments")
    RepaymentService.Receipt repay(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody RepayRequest body) {
        String me = CurrentUser.id();
        return repayments.repay(id, me, RepaymentService.Channel.WALLET,
                Money.ofMajor(body.amount(), wallets.defaultCurrency()), "wallet:" + me + ":" + key);
    }

    record RepayRequest(@NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal amount) {}
}
