package com.samwan.kibetipay.transfer.internal.web;

import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.id.Ids;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.shared.paging.Page;
import com.samwan.kibetipay.shared.time.TimeConfig;
import com.samwan.kibetipay.transfer.TransferQueries.CustomerTransferView;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler;
import com.samwan.kibetipay.transfer.internal.command.TransferCommandHandler.InitiateTransfer;
import com.samwan.kibetipay.wallet.WalletApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
class TransferController {

    private final TransferCommandHandler commands;
    private final TransferQueries queries;
    private final WalletApi wallets;

    TransferController(TransferCommandHandler commands, TransferQueries queries, WalletApi wallets) {
        this.commands = commands;
        this.queries = queries;
        this.wallets = wallets;
    }

    /**
     * 202 Accepted: the transfer is recorded and will be risk-scored and settled asynchronously.
     * Poll the Location URL (or, in a later phase, subscribe to a webhook / SSE stream).
     */
    @PostMapping
    ResponseEntity<Map<String, Object>> initiate(
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String idempotencyKey,
            @Valid @RequestBody TransferRequest body) {
        var accepted = commands.initiate(new InitiateTransfer(CurrentUser.id(), body.recipient(),
                Money.ofMajor(body.amount(), wallets.defaultCurrency()), body.note(), idempotencyKey));
        var location = URI.create("/api/transfers/" + accepted.transferId());
        return ResponseEntity.accepted()
                .location(location)
                .body(Map.of("transferId", accepted.transferId(), "txnRef", accepted.txnRef(),
                        "replayed", accepted.replayed()));
    }

    // Customer endpoints return CustomerTransferView: no risk score, no raw failure reason.

    @GetMapping("/{id}")
    CustomerTransferView get(@PathVariable UUID id) {
        return queries.byId(id, CurrentUser.id())
                .map(CustomerTransferView::of)
                .orElseThrow(() -> new DomainException.NotFound("transfer_not_found", "No transfer " + id));
    }

    /** Support/receipt lookup, e.g. GET /api/transfers/ref/KP7H3QX9M2AB (case-insensitive). */
    @GetMapping("/ref/{txnRef}")
    CustomerTransferView getByRef(@PathVariable String txnRef) {
        var ref = txnRef.trim().toUpperCase(Locale.ROOT);
        return Optional.of(ref).filter(Ids::isTxnRef)
                .flatMap(r -> queries.byTxnRef(r, CurrentUser.id()))
                .map(CustomerTransferView::of)
                .orElseThrow(() -> new DomainException.NotFound("transfer_not_found", "No transfer " + txnRef));
    }

    /**
     * The caller's transfers, newest first. Pass the previous page's {@code nextCursor} as
     * {@code cursor} for the next page; {@code from}/{@code to} are inclusive dates (Nairobi time).
     */
    @GetMapping
    Page<CustomerTransferView> history(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var query = Page.Query.of(from, to, cursor, limit, TimeConfig.BUSINESS_ZONE);
        return queries.historyFor(CurrentUser.id(), query).map(CustomerTransferView::of);
    }

    record TransferRequest(
            @NotBlank @Size(max = 64) String recipient,
            @NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal amount,
            @Size(max = 140) String note) {}
}
