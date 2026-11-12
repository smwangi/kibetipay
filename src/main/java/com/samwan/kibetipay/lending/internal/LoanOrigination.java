package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.agent.AgentApi;
import com.samwan.kibetipay.lending.LendingEvents.LoanApplied;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Command side: an agent originates a loan for a borrower. Validates, stores APPLIED and publishes
 * LoanApplied in one transaction (outbox); scoring and disbursement happen asynchronously.
 */
@Service
class LoanOrigination {

    record Originate(String agentUserId, String borrowerId, String productCode, Money principal,
                     String idempotencyKey) {}

    record Accepted(UUID loanId, boolean replayed) {}

    private final LoanRepository loans;
    private final ProductCatalog products;
    private final AgentApi agents;
    private final WalletApi wallets;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    LoanOrigination(LoanRepository loans, ProductCatalog products, AgentApi agents, WalletApi wallets,
                    JdbcClient jdbc, ApplicationEventPublisher events, TransactionTemplate tx) {
        this.loans = loans;
        this.products = products;
        this.agents = agents;
        this.wallets = wallets;
        this.jdbc = jdbc;
        this.events = events;
        this.tx = tx;
    }

    Accepted originate(Originate cmd) {
        var agent = agents.requireAgent(cmd.agentUserId());
        var existing = loans.findByAgentIdAndIdempotencyKey(agent.id(), cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }

        if (!agent.isActive()) {
            throw new DomainException.RuleViolation("agent_suspended", "Agent is suspended");
        }
        if (cmd.borrowerId().equals(agent.userId())) {
            throw new DomainException.RuleViolation("self_lending", "Agents cannot originate loans to themselves");
        }
        var product = products.require(cmd.productCode());
        long p = cmd.principal().minor();
        if (!product.active() || p < product.minPrincipalMinor() || p > product.maxPrincipalMinor()) {
            throw new DomainException.RuleViolation("principal_out_of_range",
                    "Principal must be between " + Money.of(product.minPrincipalMinor(), cmd.principal().currencyCode())
                            + " and " + Money.of(product.maxPrincipalMinor(), cmd.principal().currencyCode()));
        }
        if (!wallets.exists(cmd.borrowerId(), cmd.principal().currencyCode())) {
            throw new WalletApi.WalletNotFoundException(cmd.borrowerId(), cmd.principal().currencyCode());
        }
        if (hasOpenLoan(cmd.borrowerId())) {
            throw new DomainException.Conflict("open_loan_exists", cmd.borrowerId() + " already has an open loan");
        }

        try {
            return tx.execute(s -> {
                var loan = Loan.apply(cmd.borrowerId(), agent.id(), agent.userId(), product, cmd.principal(),
                        cmd.idempotencyKey());
                loans.saveAndFlush(loan);
                events.publishEvent(new LoanApplied(loan.id()));
                return new Accepted(loan.id(), false);
            });
        } catch (DataIntegrityViolationException e) {
            // Either a concurrent duplicate request (same key) or a concurrent second loan for the borrower.
            return loans.findByAgentIdAndIdempotencyKey(agent.id(), cmd.idempotencyKey())
                    .map(l -> replay(l, cmd))
                    .orElseThrow(() -> new DomainException.Conflict("open_loan_exists",
                            cmd.borrowerId() + " already has an open loan"));
        }
    }

    private boolean hasOpenLoan(String borrowerId) {
        return jdbc.sql("""
                        SELECT count(*) FROM lending_loan
                        WHERE borrower_id = :b AND status IN ('APPLIED', 'IN_REVIEW', 'ACTIVE')
                        """)
                .param("b", borrowerId).query(Long.class).single() > 0;
    }

    private static Accepted replay(Loan l, Originate cmd) {
        if (!l.borrowerId().equals(cmd.borrowerId()) || !l.principal().equals(cmd.principal())
                || !l.productCode().equals(cmd.productCode())) {
            throw new DomainException.RuleViolation("idempotency_key_reused",
                    "Idempotency-Key was already used for a different loan");
        }
        return new Accepted(l.id(), true);
    }
}
