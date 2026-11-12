package com.samwan.kibetipay.transfer.internal.web;

import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.transfer.TransferQueries.TransferView;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
                .body(Map.of("transferId", accepted.transferId(), "replayed", accepted.replayed()));
    }

    @GetMapping("/{id}")
    TransferView get(@PathVariable UUID id) {
        return queries.byId(id, CurrentUser.id())
                .orElseThrow(() -> new DomainException.NotFound("transfer_not_found", "No transfer " + id));
    }

    @GetMapping
    List<TransferView> recent(@RequestParam(defaultValue = "20") int limit) {
        return queries.recentFor(CurrentUser.id(), limit);
    }

    record TransferRequest(
            @NotBlank @Size(max = 64) String recipient,
            @NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal amount,
            @Size(max = 140) String note) {}
}
