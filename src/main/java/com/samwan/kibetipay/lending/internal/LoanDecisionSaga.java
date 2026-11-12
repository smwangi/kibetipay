package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.LendingJournalKinds;
import com.samwan.kibetipay.agent.AgentApi;
import com.samwan.kibetipay.lending.LendingEvents.LoanApplied;
import com.samwan.kibetipay.lending.LendingEvents.LoanDeclined;
import com.samwan.kibetipay.lending.LendingEvents.LoanDisbursed;
import com.samwan.kibetipay.risk.RiskApi;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.JournalRequest;
import com.samwan.kibetipay.wallet.WalletApi.Leg;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loan approval saga: credit score ─▶ agent limit ─▶ fraud check ─▶ disburse.
 * Same crash-safety recipe as transfers: every step is idempotent and its own transaction,
 * the ledger posting is keyed by "loan:disburse:{id}", and the event registry replays on restart.
 */
@Component
class LoanDecisionSaga {

    private final LoanRepository loans;
    private final ProductCatalog products;
    private final CreditModel creditModel;
    private final AgentApi agents;
    private final RiskApi risk;
    private final WalletApi wallets;
    private final JdbcClient jdbc;
    private final LendingProperties props;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry metrics;

    LoanDecisionSaga(LoanRepository loans, ProductCatalog products, CreditModel creditModel, AgentApi agents,
                     RiskApi risk, WalletApi wallets, JdbcClient jdbc, LendingProperties props,
                     ApplicationEventPublisher events, TransactionTemplate tx, Clock clock, MeterRegistry metrics) {
        this.loans = loans;
        this.products = products;
        this.creditModel = creditModel;
        this.agents = agents;
        this.risk = risk;
        this.wallets = wallets;
        this.jdbc = jdbc;
        this.props = props;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Async
    @TransactionalEventListener
    @Observed(name = "lending.decide")
    void on(LoanApplied event) {
        decide(event.loanId());
    }

    void decide(UUID loanId) {
        var loan = loans.findById(loanId).orElseThrow();
        if (loan.status() != Loan.Status.APPLIED) {
            return;   // already decided: replayed event
        }
        var product = products.require(loan.productCode());

        // 1. Credit score (borrower's ability and willingness to repay).
        var score = creditModel.score(loan.borrowerId(), product, loan.principal().minor());
        String reasons = creditModel.name() + ": " + String.join(", ", score.reasons());
        tx.executeWithoutResult(s -> loans.findById(loanId).orElseThrow().scored(score.value(), reasons));
        if (score.hardDecline() || score.value() < props.reviewScore()) {
            decline(loanId, "credit: " + reasons);
            return;
        }

        // 2. Agent exposure: an agent's outstanding book may not exceed its lending limit.
        var agent = agents.get(loan.agentId());
        long exposure = agentExposure(agent.id()) + loan.principal().minor();
        if (exposure > agent.lendingLimit().minor()) {
            decline(loanId, "agent_limit_exceeded");
            return;
        }

        // 3. Fraud signals reuse the risk module: the subject is the AGENT (pushy agents, ghost
        //    borrowers, origination velocity), the balance is the pool (draining it is suspicious).
        var pool = wallets.get(SystemAccounts.LENDING_POOL, loan.currency()).balance();
        var fraud = risk.assess(new RiskApi.RiskRequest(loanId, agent.userId(), loan.borrowerId(),
                loan.principal(), pool, loan.appliedAt()));
        switch (fraud.outcome()) {
            case REJECT -> {
                decline(loanId, "fraud: " + String.join(", ", fraud.reasons()));
                return;
            }
            case REVIEW -> {
                holdForReview(loanId);
                return;
            }
            case APPROVE -> { }
        }

        // 4. Borderline credit goes to a human; strong credit is auto-disbursed.
        if (score.value() < props.approveScore()) {
            holdForReview(loanId);
        } else {
            disburse(loanId);
        }
    }

    /** Also called from the admin review queue. */
    void disburse(UUID loanId) {
        var loan = loans.findById(loanId).orElseThrow();
        if (loan.status() == Loan.Status.ACTIVE) {
            return;
        }
        WalletApi.PostingReceipt receipt;
        try {
            receipt = wallets.postJournal(new JournalRequest("loan:disburse:" + loanId, LendingJournalKinds.LOAN_DISBURSEMENT, List.of(
                    Leg.debit(SystemAccounts.LENDING_POOL, loan.principal()),
                    Leg.credit(loan.borrowerId(), loan.principal()))));
        } catch (WalletApi.InsufficientFundsException poolEmpty) {
            decline(loanId, "lending_pool_exhausted");
            return;
        }
        var product = products.require(loan.productCode());
        tx.executeWithoutResult(s -> {
            var l = loans.findById(loanId).orElseThrow();
            if (l.status() == Loan.Status.ACTIVE) {
                return;
            }
            l.activate(receipt.journalId(), product, LocalDate.now(clock));
            events.publishEvent(new LoanDisbursed(l.id(), l.borrowerId(), l.agentId(), l.principal().minor(),
                    l.currency(), Instant.now(clock)));
        });
        metrics.counter("kibetipay.loans", "outcome", "disbursed").increment();
    }

    void decline(UUID loanId, String reasons) {
        tx.executeWithoutResult(s -> {
            var l = loans.findById(loanId).orElseThrow();
            if (l.status() == Loan.Status.DECLINED) {
                return;
            }
            l.decline(reasons);
            events.publishEvent(new LoanDeclined(l.id(), l.borrowerId(), reasons, Instant.now(clock)));
        });
        metrics.counter("kibetipay.loans", "outcome", "declined").increment();
    }

    private void holdForReview(UUID loanId) {
        tx.executeWithoutResult(s -> loans.findById(loanId).orElseThrow().holdForReview());
        metrics.counter("kibetipay.loans", "outcome", "review").increment();
    }

    void requireInReview(UUID loanId) {
        var l = loans.findById(loanId)
                .orElseThrow(() -> new DomainException.NotFound("loan_not_found", "No loan " + loanId));
        if (l.status() != Loan.Status.IN_REVIEW) {
            throw new DomainException.Conflict("not_in_review", "Loan is " + l.status());
        }
    }

    private long agentExposure(UUID agentId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(i.principal_due - i.principal_paid), 0)
                        FROM lending_installment i JOIN lending_loan l ON l.id = i.loan_id
                        WHERE l.agent_id = :a AND l.status IN ('ACTIVE', 'DEFAULTED')
                        """)
                .param("a", agentId).query(Long.class).single();
    }
}
