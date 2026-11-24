package com.samwan.kibetipay.lending.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.TestcontainersConfig;
import com.samwan.kibetipay.agent.AgentApi;
import com.samwan.kibetipay.agent.AgentApi.NewAgent;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.support.Race;
import com.samwan.kibetipay.wallet.WalletApi;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Repayments racing on one loan: the loan row lock must ensure the same shilling is never owed,
 * collected or paid out as commission twice.
 */
@Tag("financial")
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.insights.enabled=false",
        "app.assistant.ingest-on-startup=false",
        "spring.ai.ollama.init.pull-model-strategy=never",
        "spring.docker.compose.enabled=false"
})
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RepaymentConcurrencyIntegrationTest {

    private static final String KES = "KES";

    @MockitoBean EmbeddingModel embeddingModel;

    @Autowired AgentApi agents;
    @Autowired WalletApi wallets;
    @Autowired PoolFundingService pool;
    @Autowired LoanOrigination origination;
    @Autowired LoanOfferService offers;
    @Autowired LoanRepository loans;
    @Autowired RepaymentService repayments;
    @Autowired JdbcClient jdbc;
    @Autowired LendingProperties props;

    private String borrower;
    private String agentUser;
    private String uplineUser;
    private UUID loanId;

    @BeforeEach
    void activeLoan() throws InterruptedException {
        String run = UUID.randomUUID().toString().substring(0, 8);
        uplineUser = "fin-top-" + run;
        agentUser = "fin-agent-" + run;
        borrower = "fin-borrower-" + run;
        agents.registerTopAgent(new NewAgent(uplineUser, "Top " + run, "Nairobi", kes(50_000_000)));
        agents.registerSubAgent(uplineUser, new NewAgent(agentUser, "Agent " + run, "Kisumu", kes(10_000_000)));
        wallets.open(borrower, KES);
        wallets.deposit(borrower, kes(20_000_00), "deposit:" + borrower);   // savings buffer + repayment funds
        pool.fund(kes(1_000_000_00), "fin-" + run);

        loanId = origination.originate(new LoanOrigination.Originate(agentUser, borrower, "SOKO-28", kes(3_000_00),
                "loan-" + run)).loanId();
        offers.respond(loanId, borrower, true);
        awaitStatus(Loan.Status.ACTIVE);
    }

    /** Every racer tries to pay off the whole loan: exactly one may succeed. */
    @Test
    void racingFullRepaymentsCollectOnce() throws Exception {
        long outstanding = outstanding();
        long walletBefore = balance(borrower);

        var outcomes = Race.run(8, 8, i -> () -> repayments.repay(loanId, borrower, RepaymentService.Channel.WALLET,
                kes(outstanding), "rep-full-" + loanId + "-" + i));

        var paid = Race.successes(outcomes, RepaymentService.Receipt.class);
        assertThat(paid).hasSize(1).first().satisfies(r -> assertThat(r.loanClosed()).isTrue());
        // Losers see a loan that is already repaid: nothing left to allocate, or no longer repayable.
        assertThat(Race.failures(outcomes)).hasSize(7).allSatisfy(e -> {
            assertThat(e).isInstanceOf(DomainException.class);
            assertThat(((DomainException) e).code()).isIn("overpayment", "invalid_loan_state");
        });
        assertThat(balance(borrower)).isEqualTo(walletBefore - outstanding);
        assertThat(outstanding()).isZero();
        assertThat(loans.findById(loanId).orElseThrow().status()).isEqualTo(Loan.Status.REPAID);
        assertThat(count("SELECT count(*) FROM lending_repayment WHERE loan_id = :id")).isEqualTo(1);
        assertCommissionMatchesCollectedRevenue();
    }

    /** One logical repayment retried from several threads: one posting, the rest replays. */
    @Test
    void sameReferenceRaceRepaysOnce() throws Exception {
        long walletBefore = balance(borrower);
        long outstandingBefore = outstanding();
        String reference = "rep-same-" + loanId;

        var outcomes = Race.run(8, 8, i -> () -> repayments.repay(loanId, borrower, RepaymentService.Channel.WALLET,
                kes(500_00), reference));

        // The loan row lock serialises the racers, so the losers find the first repayment and replay it.
        assertThat(Race.failures(outcomes)).isEmpty();
        var receipts = Race.successes(outcomes, RepaymentService.Receipt.class);
        assertThat(receipts.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(receipts).extracting(RepaymentService.Receipt::repaymentId).containsOnly(receipts.getFirst().repaymentId());
        assertThat(balance(borrower)).isEqualTo(walletBefore - 500_00);
        assertThat(outstanding()).isEqualTo(outstandingBefore - 500_00);
        assertThat(count("SELECT count(*) FROM wallet_journal WHERE reference = 'loan:repay:" + reference + "'")).isEqualTo(1);
        assertCommissionMatchesCollectedRevenue();
    }

    /**
     * Commission is a configured share of collected interest + fees, split up the lineage. Whatever the
     * interleaving, what was paid to agents must equal the rate applied per repayment, and the
     * commission journals must match the per-agent commission rows exactly.
     */
    private void assertCommissionMatchesCollectedRevenue() {
        long expectedPool = jdbc.sql("""
                        SELECT coalesce(sum((interest_minor + fee_minor) * :bps / 10000), 0)
                        FROM lending_repayment WHERE loan_id = :id
                        """).param("id", loanId).param("bps", props.commissionRateBps()).query(Long.class).single();
        long rows = count("""
                SELECT coalesce(sum(c.amount_minor), 0) FROM lending_commission c
                JOIN lending_repayment r ON r.id = c.repayment_id WHERE r.loan_id = :id
                """);
        long credited = count("""
                SELECT coalesce(sum(e.amount_minor), 0)
                FROM wallet_ledger_entry e JOIN wallet_journal j ON j.id = e.journal_id
                JOIN wallet_account w ON w.id = e.wallet_id
                WHERE j.kind = 'COMMISSION' AND e.direction = 'CREDIT' AND w.owner_id IN (:agent, :upline)
                  AND j.reference IN (SELECT 'loan:commission:' || r.id FROM lending_repayment r WHERE r.loan_id = :id)
                """);
        assertThat(rows).as("commission rows").isEqualTo(expectedPool);
        assertThat(credited).as("commission credited to agent wallets").isEqualTo(expectedPool);
    }

    private long count(String sql) {
        return jdbc.sql(sql).param("id", loanId).param("agent", agentUser).param("upline", uplineUser)
                .query(Long.class).single();
    }

    private long outstanding() {
        return jdbc.sql("""
                        SELECT coalesce(sum(principal_due + interest_due + fee_due + penalty_due
                               - principal_paid - interest_paid - fee_paid - penalty_paid), 0)
                        FROM lending_installment WHERE loan_id = :id
                        """).param("id", loanId).query(Long.class).single();
    }

    private void awaitStatus(Loan.Status expected) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(20));
        Loan.Status status = null;
        while (Instant.now().isBefore(deadline)) {
            status = loans.findById(loanId).orElseThrow().status();
            if (status == expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Loan " + loanId + " is " + status + ", expected " + expected);
    }

    private long balance(String owner) {
        return wallets.get(owner, KES).balance().minor();
    }

    private static Money kes(long minor) {
        return Money.of(minor, KES);
    }
}
