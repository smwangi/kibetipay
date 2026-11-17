package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.wallet.WalletApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CollectionController {
    private final CollectionService collections;
    private final WalletApi wallets;
    CollectionController(CollectionService collections, WalletApi wallets) { this.collections = collections; this.wallets = wallets; }
    record Request(@NotBlank @Pattern(regexp = "254[17]\\d{8}") String msisdn,
                   @NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal amount) {}

    @PostMapping("/api/mobile-money/collections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CollectionService.Result initiate(@RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String key,
                                      @Valid @RequestBody Request request) {
        return collections.initiate(CurrentUser.id(), key, request.msisdn(), Money.ofMajor(request.amount(), wallets.defaultCurrency()));
    }

    @GetMapping("/api/mobile-money/collections/{id}")
    CollectionService.Result get(@PathVariable UUID id) { return collections.get(id, CurrentUser.id()); }
}
