package com.samwan.kibetipay;

import com.samwan.kibetipay.lending.LendingJournalKinds;
import com.samwan.kibetipay.agent.AgentApi;
import com.samwan.kibetipay.agent.AgentApi.NewAgent;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.JournalRequest;
import com.samwan.kibetipay.wallet.WalletApi.Leg;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-only demo data, built through the public module APIs (never raw SQL), so it exercises the
 * same rules as real traffic. Idempotent: every posting has a fixed reference, so restarts are safe.
 *
 * Network: wanjiru (agent, Nairobi) └─ otieno (sub-agent, Kisumu). Borrowers: alice, bob.
 */
@Component
@Profile("dev")
class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final WalletApi wallets;
    private final AgentApi agents;

    DevDataSeeder(WalletApi wallets, AgentApi agents) {
        this.wallets = wallets;
        this.agents = agents;
    }

    @Override
    public void run(ApplicationArguments args) {
        String kes = wallets.defaultCurrency();
        for (String user : List.of("alice", "bob")) {
            if (!wallets.exists(user, kes)) {
                wallets.open(user, kes);
            }
        }
        wallets.deposit("alice", Money.of(1_000_000, kes), "seed:deposit:alice");   // KES 10,000
        wallets.deposit("bob", Money.of(300_000, kes), "seed:deposit:bob");         // KES 3,000

        if (agents.byUser("wanjiru").isEmpty()) {
            agents.registerTopAgent(new NewAgent("wanjiru", "Wanjiru Agencies", "Nairobi", Money.of(50_000_000, kes)));
        }
        if (agents.byUser("otieno").isEmpty()) {
            agents.registerSubAgent("wanjiru", new NewAgent("otieno", "Otieno Kisumu", "Kisumu", Money.of(10_000_000, kes)));
        }
        // Agent float so agents can record cash collections.
        wallets.deposit("wanjiru", Money.of(2_000_000, kes), "seed:float:wanjiru");
        wallets.deposit("otieno", Money.of(1_000_000, kes), "seed:float:otieno");

        // Capital: KES 1,000,000 into the lending pool.
        var capital = Money.of(100_000_000, kes);
        wallets.postJournal(new JournalRequest("seed:pool:capital", LendingJournalKinds.CAPITAL,
                List.of(Leg.debit(SystemAccounts.FLOAT, capital), Leg.credit(SystemAccounts.LENDING_POOL, capital))));
        log.info("Dev data ready: agents wanjiru > otieno, borrowers alice & bob, pool funded");
    }
}
