package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.LendingQueries.LoanSummary;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Platform admin: capital, review queue, end-of-day job, whole-book reporting. */
@RestController
class LendingAdminController {

    private final PoolFundingService poolFunding;
    private final WalletApi wallets;
    private final LoanDecisionSaga saga;
    private final LoanQueryService queries;
    private final LoanDailyJobs jobs;
    private final Clock clock;

    LendingAdminController(PoolFundingService poolFunding, WalletApi wallets, LoanDecisionSaga saga,
                           LoanQueryService queries, LoanDailyJobs jobs, Clock clock) {
        this.poolFunding = poolFunding;
        this.wallets = wallets;
        this.saga = saga;
        this.queries = queries;
        this.jobs = jobs;
        this.clock = clock;
    }

    /** Capital injection: money moves from the settlement float into the lending pool. */
    @PostMapping("/api/admin/lending/pool/fund")
    @ResponseStatus(HttpStatus.CREATED)
    PoolFundingService.Funded fundPool(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody FundPoolRequest body) {
        return poolFunding.fund(Money.ofMajor(body.amount(), wallets.defaultCurrency()), key);
    }

    @GetMapping("/api/admin/lending/review-queue")
    List<LoanSummary> reviewQueue() {
        return queries.inReview();
    }

    @PostMapping("/api/admin/lending/loans/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(@PathVariable UUID id) {
        saga.requireInReview(id);
        saga.disburse(id);
    }

    @PostMapping("/api/admin/lending/loans/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@PathVariable UUID id, @Valid @RequestBody DeclineRequest body) {
        saga.requireInReview(id);
        saga.decline(id, "analyst: " + body.reason());
    }

    /** Run end-of-day for a business date (defaults to today). Handy for demos: "fast-forward" a loan into arrears. */
    @PostMapping("/api/admin/lending/jobs/eod")
    LoanDailyJobs.Result runEndOfDay(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                     LocalDate date) {
        return jobs.run(date != null ? date : LocalDate.now(clock));
    }

    @GetMapping("/api/admin/lending/portfolio")
    LoanQueryService.Portfolio wholeBook() {
        return queries.portfolio(null, null);   // null = whole book, no cross-module SQL needed
    }

    record FundPoolRequest(@NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal amount) {}

    record DeclineRequest(@NotBlank @Size(max = 200) String reason) {}
}
