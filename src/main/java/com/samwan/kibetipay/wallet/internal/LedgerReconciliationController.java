package com.samwan.kibetipay.wallet.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only: run the ledger reconciliation now instead of waiting for the schedule. Read-only. */
@RestController
class LedgerReconciliationController {

    private final LedgerReconciliation reconciliation;

    LedgerReconciliationController(LedgerReconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/api/admin/ledger/reconciliation")
    LedgerReconciliation.Report run() {
        return reconciliation.run();
    }
}
