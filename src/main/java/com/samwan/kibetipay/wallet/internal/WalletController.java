package com.samwan.kibetipay.wallet.internal;

import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.paging.Page;
import com.samwan.kibetipay.shared.time.TimeConfig;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.PostingReceipt;
import com.samwan.kibetipay.wallet.WalletApi.StatementLine;
import com.samwan.kibetipay.wallet.WalletApi.WalletSummary;
import com.samwan.kibetipay.wallet.WalletApi.WalletView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets")
class WalletController {

    private final WalletApi wallets;
    private final LedgerProperties props;

    WalletController(WalletApi wallets, LedgerProperties props) {
        this.wallets = wallets;
        this.props = props;
    }

    @PostMapping
    ResponseEntity<WalletView> open() {
        var view = wallets.open(CurrentUser.id(), props.defaultCurrency());
        return ResponseEntity.created(URI.create("/api/wallets/me")).body(view);
    }

    @GetMapping("/me")
    WalletView mine() {
        return wallets.get(CurrentUser.id(), props.defaultCurrency());
    }

    /**
     * Simulated top-up. In production this is driven by a payment-provider webhook
     * (e.g. M-Pesa C2B confirmation), and the provider's receipt number is the reference.
     */
    @PostMapping("/me/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    PostingReceipt deposit(@RequestHeader("Idempotency-Key") String idempotencyKey,
                           @Valid @RequestBody DepositRequest body) {
        var amount = Money.ofMajor(body.amount(), props.defaultCurrency());
        return wallets.deposit(CurrentUser.id(), amount, "deposit:" + CurrentUser.id() + ":" + idempotencyKey);
    }

    /**
     * Every ledger movement on the caller's wallet, newest first. Pass the previous page's
     * {@code nextCursor} as {@code cursor}; {@code from}/{@code to} are inclusive dates (Nairobi time).
     */
    @GetMapping("/me/statement")
    Page<StatementLine> statement(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var query = Page.Query.of(from, to, cursor, limit, TimeConfig.BUSINESS_ZONE);
        return wallets.statement(CurrentUser.id(), props.defaultCurrency(), query);
    }

    /** Dashboard figures: money in/out by kind and a daily balance series for the last {@code days} days. */
    @GetMapping("/me/summary")
    WalletSummary summary(@RequestParam(defaultValue = "30") int days) {
        return wallets.summary(CurrentUser.id(), props.defaultCurrency(), days, TimeConfig.BUSINESS_ZONE);
    }

    record DepositRequest(
            @NotNull @DecimalMin("1.00") @DecimalMax("150000.00") @Digits(integer = 9, fraction = 2)
            BigDecimal amount) {}
}
