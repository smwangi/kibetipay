package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.mobilemoney.MobileMoneyGateway;
import com.samwan.kibetipay.shared.error.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Local development only; not a provider webhook. Protected by the admin security rule. */
@RestController
@Profile("dev")
class SimulatedCollectionController {
    private final CollectionService collections;
    private final MobileMoneyGateway gateway;
    SimulatedCollectionController(CollectionService collections, MobileMoneyGateway gateway) { this.collections = collections; this.gateway = gateway; }
    record Callback(@NotBlank @Size(max = 100) String providerRequestId, @Positive long amountMinor,
                    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency, @NotNull Boolean success,
                    @Size(max = 100) String receipt) {}

    @PostMapping("/api/admin/mobile-money/simulator/collections/{id}/result")
    CollectionService.Result result(@PathVariable UUID id, @Valid @RequestBody Callback callback) {
        if (!SimulatedMobileMoneyGateway.PROVIDER.equals(gateway.provider())) {
            throw new DomainException.Conflict("simulator_unavailable", "Simulated results require the simulator gateway");
        }
        return collections.complete(id, callback.providerRequestId(), callback.amountMinor(), callback.currency(),
                callback.success(), callback.receipt());
    }
}
