package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.LendingJournalKinds;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.JournalRequest;
import com.samwan.kibetipay.wallet.WalletApi.Leg;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Capital injection: moves money from the settlement float into the lending pool with one
 * balanced journal. Idempotent on the caller's key, because the wallet ledger dedupes on reference.
 */
@Service
class PoolFundingService {

    record Funded(UUID journalId, boolean replayed, String poolBalance) {}

    private final WalletApi wallets;

    PoolFundingService(WalletApi wallets) {
        this.wallets = wallets;
    }

    Funded fund(Money amount, String idempotencyKey) {
        var receipt = wallets.postJournal(new JournalRequest("pool:fund:" + idempotencyKey, LendingJournalKinds.CAPITAL, List.of(
                Leg.debit(SystemAccounts.FLOAT, amount), Leg.credit(SystemAccounts.LENDING_POOL, amount))));
        var pool = wallets.get(SystemAccounts.LENDING_POOL, amount.currency().getCurrencyCode()).balance();
        return new Funded(receipt.journalId(), receipt.replayed(), pool.toString());
    }
}
