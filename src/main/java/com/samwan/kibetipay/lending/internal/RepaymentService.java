package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.LendingJournalKinds;
import com.samwan.kibetipay.agent.AgentApi;
import com.samwan.kibetipay.lending.LendingEvents.LoanRepaid;
import com.samwan.kibetipay.lending.LendingEvents.RepaymentReceived;
import com.samwan.kibetipay.lending.internal.calc.CommissionSplitter;
import com.samwan.kibetipay.lending.internal.calc.RepaymentAllocator;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import com.samwan.kibetipay.wallet.WalletApi.JournalRequest;
import com.samwan.kibetipay.wallet.WalletApi.Leg;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import io.micrometer.observation.annotation.Observed;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a payment, runs the waterfall, posts ONE balanced journal that splits the cash into
 * pool (principal), interest income, fee income and penalty income, then pays agent
 * commission up the lineage in a second journal. All in one DB transaction with the loan
 * row locked, so two simultaneous payments can never both think the same shilling is owed.
 */
@Service
class RepaymentService {

    enum Channel { WALLET, AGENT_CASH }

    record Receipt(UUID repaymentId, UUID loanId, String paid, String penalty, String fee, String interest,
                   String principal, String outstanding, boolean loanClosed, boolean replayed) {}

    private final LoanRepository loans;
    private final AgentApi agents;
    private final WalletApi wallets;
    private final JdbcClient jdbc;
    private final LendingProperties props;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    RepaymentService(LoanRepository loans, AgentApi agents, WalletApi wallets, JdbcClient jdbc,
                     LendingProperties props, ApplicationEventPublisher events, Clock clock) {
        this.loans = loans;
        this.agents = agents;
        this.wallets = wallets;
        this.jdbc = jdbc;
        this.props = props;
        this.events = events;
        this.clock = clock;
    }

    @Observed(name = "lending.repay")
    @Transactional
    Receipt repay(UUID loanId, String payerId, Channel channel, Money amount, String reference) {
        var replay = findByReference(reference);
        if (replay != null) {
            return replay;
        }

        var loan = loans.findForUpdate(loanId)
                .orElseThrow(() -> new DomainException.NotFound("loan_not_found", "No loan " + loanId));
        authorise(loan, payerId, channel);

        RepaymentAllocator.Allocation a;
        try {
            a = RepaymentAllocator.allocate(loan.outstandingOldestFirst(), amount.minor());
        } catch (RepaymentAllocator.OverpaymentException e) {
            throw new DomainException.RuleViolation("overpayment",
                    "Payment exceeds outstanding balance " + Money.of(e.outstanding, amount.currencyCode()));
        }

        String cur = amount.currencyCode();
        // Cash collected by an agent is debited from the agent's float wallet: the agent physically holds the cash.
        wallets.postJournal(new JournalRequest("loan:repay:" + reference, LendingJournalKinds.LOAN_REPAYMENT, List.of(
                Leg.debit(payerId, amount),
                Leg.credit(SystemAccounts.LENDING_POOL, Money.of(a.principal(), cur)),
                Leg.credit(SystemAccounts.INTEREST_INCOME, Money.of(a.interest(), cur)),
                Leg.credit(SystemAccounts.FEE_INCOME, Money.of(a.fee(), cur)),
                Leg.credit(SystemAccounts.PENALTY_INCOME, Money.of(a.penalty(), cur)))));

        boolean closed = loan.applyRepayment(a);
        var repaymentId = UUID.randomUUID();
        var now = Instant.now(clock);
        jdbc.sql("""
                        INSERT INTO lending_repayment (id, loan_id, reference, payer_id, channel, amount_minor,
                            penalty_minor, fee_minor, interest_minor, principal_minor, created_at)
                        VALUES (:id, :loan, :ref, :payer, :channel, :amount, :pen, :fee, :int, :prin, :at)
                        """)
                .param("id", repaymentId).param("loan", loanId).param("ref", reference).param("payer", payerId)
                .param("channel", channel.name()).param("amount", amount.minor())
                .param("pen", a.penalty()).param("fee", a.fee()).param("int", a.interest()).param("prin", a.principal())
                .param("at", Timestamp.from(now))
                .update();

        payCommission(loan, repaymentId, a.interest() + a.fee(), cur, now);

        events.publishEvent(new RepaymentReceived(loanId, repaymentId, loan.borrowerId(), channel.name(),
                amount.minor(), now));
        if (closed) {
            events.publishEvent(new LoanRepaid(loanId, loan.borrowerId(), loan.agentId(), now));
        }
        return receipt(repaymentId, loanId, amount.minor(), a.penalty(), a.fee(), a.interest(), a.principal(),
                loan.outstanding(), closed, false, cur);
    }

    /**
     * Commission is paid on what was actually COLLECTED (interest + fees), not on disbursement:
     * agents are rewarded for loans that get repaid, which aligns incentives against reckless lending.
     */
    private void payCommission(Loan loan, UUID repaymentId, long revenue, String cur, Instant now) {
        long pool = Math.multiplyExact(revenue, props.commissionRateBps()) / 10_000;
        if (pool == 0) {
            return;
        }
        var lineage = agents.lineage(loan.agentId());
        var shares = CommissionSplitter.split(pool, props.commissionSplit(), lineage.size());

        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit(SystemAccounts.COMMISSION_EXPENSE, Money.of(pool, cur)));
        for (int level = 0; level < shares.size(); level++) {
            var agent = lineage.get(level);
            long share = shares.get(level);
            legs.add(Leg.credit(agent.userId(), Money.of(share, cur)));
            jdbc.sql("""
                            INSERT INTO lending_commission (id, repayment_id, agent_id, agent_user_id, level, amount_minor, created_at)
                            VALUES (:id, :rep, :agent, :user, :level, :amount, :at)
                            """)
                    .param("id", UUID.randomUUID()).param("rep", repaymentId).param("agent", agent.id())
                    .param("user", agent.userId()).param("level", level).param("amount", share)
                    .param("at", Timestamp.from(now))
                    .update();
        }
        wallets.postJournal(new JournalRequest("loan:commission:" + repaymentId, LendingJournalKinds.COMMISSION, legs));
    }

    private void authorise(Loan loan, String payerId, Channel channel) {
        switch (channel) {
            case WALLET -> {
                if (!loan.borrowerId().equals(payerId)) {
                    throw new DomainException.NotFound("loan_not_found", "No loan " + loan.id());   // don't leak existence
                }
            }
            case AGENT_CASH -> {
                boolean inLineage = agents.lineage(loan.agentId()).stream().anyMatch(a -> a.userId().equals(payerId));
                if (!inLineage) {
                    throw new DomainException.RuleViolation("not_loan_agent",
                            "Only the originating agent or its upline can collect on this loan");
                }
            }
        }
    }

    private Receipt findByReference(String reference) {
        return jdbc.sql("""
                        SELECT r.id, r.loan_id, r.amount_minor, r.penalty_minor, r.fee_minor, r.interest_minor,
                               r.principal_minor, l.status, l.currency,
                               (SELECT coalesce(sum(principal_due + interest_due + fee_due + penalty_due
                                       - principal_paid - interest_paid - fee_paid - penalty_paid), 0)
                                FROM lending_installment WHERE loan_id = r.loan_id) AS outstanding
                        FROM lending_repayment r JOIN lending_loan l ON l.id = r.loan_id
                        WHERE r.reference = :ref
                        """)
                .param("ref", reference)
                .query((rs, i) -> receipt(rs.getObject("id", UUID.class), rs.getObject("loan_id", UUID.class),
                        rs.getLong("amount_minor"), rs.getLong("penalty_minor"), rs.getLong("fee_minor"),
                        rs.getLong("interest_minor"), rs.getLong("principal_minor"), rs.getLong("outstanding"),
                        Loan.Status.REPAID.name().equals(rs.getString("status")), true, rs.getString("currency")))
                .optional().orElse(null);
    }

    private static Receipt receipt(UUID id, UUID loanId, long paid, long pen, long fee, long interest, long principal,
                                   long outstanding, boolean closed, boolean replayed, String cur) {
        return new Receipt(id, loanId, fmt(paid, cur), fmt(pen, cur), fmt(fee, cur), fmt(interest, cur),
                fmt(principal, cur), fmt(outstanding, cur), closed, replayed);
    }

    private static String fmt(long minor, String cur) {
        return Money.of(minor, cur).toMajor().toPlainString();
    }
}
