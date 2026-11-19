package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only suspense workflow (protected by the /api/admin/** security rule). These calls post to the ledger. */
@RestController
class SuspenseController {
    private final SuspenseService suspense;

    SuspenseController(SuspenseService suspense) { this.suspense = suspense; }

    record Open(@NotBlank @Size(max = 500) String reason) {}
    record Allocate(@NotBlank @Size(max = 64) String ownerId, @NotBlank @Size(max = 500) String note) {}

    @PostMapping("/api/admin/mobile-money/provider-transactions/{id}/suspense")
    SuspenseService.Item open(@PathVariable UUID id, @Valid @RequestBody Open request) {
        return suspense.open(id, request.reason(), CurrentUser.id());
    }

    @PostMapping("/api/admin/mobile-money/suspense/{id}/allocation")
    SuspenseService.Item allocate(@PathVariable UUID id, @Valid @RequestBody Allocate request) {
        return suspense.allocate(id, request.ownerId(), request.note(), CurrentUser.id());
    }

    @GetMapping("/api/admin/mobile-money/suspense")
    List<SuspenseService.Item> list(@RequestParam(required = false) SuspenseService.Status status) {
        return suspense.list(status);
    }
}
