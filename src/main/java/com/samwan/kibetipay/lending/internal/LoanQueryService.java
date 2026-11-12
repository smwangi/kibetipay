package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.LendingQueries;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side: SQL straight into DTOs, including the portfolio-at-risk (PAR) report. */
@Service
@Transactional(readOnly = true)
class LoanQueryService implements LendingQueries {

    private static final String SUMMARY = """
            SELECT l.id, p.name AS product, l.principal_minor, l.interest_minor, l.fee_minor, l.currency,
                   l.status, l.credit_score, l.applied_at, l.borrower_id, l.agent_user_id, l.decision_reasons,
                   coalesce(sum(i.principal_due + i.interest_due + i.fee_due + i.penalty_due
                                - i.principal_paid - i.interest_paid - i.fee_paid - i.penalty_paid), 0) AS outstanding,
                   min(i.due_date) FILTER (WHERE i.status <> 'PAID') AS next_due,
                   min(i.due_date) FILTER (WHERE i.status = 'OVERDUE') AS oldest_overdue
            FROM lending_loan l
            JOIN lending_product p ON p.code = l.product_code
            LEFT JOIN lending_installment i ON i.loan_id = l.id
            """;
    private static final String GROUP = " GROUP BY l.id, p.name ";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final WalletApi wallets;

    LoanQueryService(JdbcClient jdbc, Clock clock, WalletApi wallets) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.wallets = wallets;
    }

    @Override
    public List<LoanSummary> loansOf(String borrowerId) {
        return jdbc.sql(SUMMARY + " WHERE l.borrower_id = :b" + GROUP + " ORDER BY l.applied_at DESC")
                .param("b", borrowerId).query(this::summary).list();
    }

    @Override
    public Optional<LoanDetail> loan(UUID loanId, String viewerId) {
        // Visible to the borrower and to the originating agent.
        return jdbc.sql(SUMMARY + " WHERE l.id = :id AND (l.borrower_id = :v OR l.agent_user_id = :v)" + GROUP)
                .param("id", loanId).param("v", viewerId)
                .query((rs, i) -> new LoanDetail(summary(rs, i), rs.getString("borrower_id"),
                        rs.getString("agent_user_id"), rs.getString("decision_reasons"), List.of()))
                .optional()
                .map(d -> new LoanDetail(d.summary(), d.borrowerId(), d.agentUserId(), d.decisionReasons(),
                        schedule(loanId, currencyOf(loanId))));
    }

    List<LoanSummary> inReview() {
        return jdbc.sql(SUMMARY + " WHERE l.status = 'IN_REVIEW'" + GROUP + " ORDER BY l.applied_at")
                .query(this::summary).list();
    }

    /**
     * Portfolio at risk: the share of outstanding principal sitting in loans with any installment
     * more than N days late. PAR30 is THE health metric for a microfinance book.
     */
    record Portfolio(int activeLoans, String outstandingPrincipal, String par30Principal, double par30Ratio,
                     int defaultedLoans, String collected, String commissionEarned) {}

    /** @param agentIds agents to include, or {@code null} for the whole book */
    Portfolio portfolio(Collection<UUID> agentIds, String commissionForUser) {
        LocalDate cutoff = LocalDate.now(clock).minusDays(30);
        String agentFilter = agentIds == null ? "" : " AND l.agent_id IN (:agents)";
        var row = jdbc.sql("""
                        WITH book AS (
                            SELECT l.id, l.status, l.currency,
                                   sum(i.principal_due - i.principal_paid) AS principal_out,
                                   bool_or(i.status = 'OVERDUE' AND i.due_date < :cutoff) AS par30
                            FROM lending_loan l JOIN lending_installment i ON i.loan_id = l.id
                            WHERE l.status IN ('ACTIVE', 'DEFAULTED', 'REPAID') %s
                            GROUP BY l.id
                        )
                        SELECT count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                               count(*) FILTER (WHERE status = 'DEFAULTED') AS defaulted,
                               coalesce(sum(principal_out) FILTER (WHERE status IN ('ACTIVE', 'DEFAULTED')), 0) AS outstanding,
                               coalesce(sum(principal_out) FILTER (WHERE par30 AND status IN ('ACTIVE', 'DEFAULTED')), 0) AS par30
                        FROM book
                        """.formatted(agentFilter))
                .paramSource(params(cutoff, agentIds))
                .query((rs, i) -> new long[] {rs.getLong("active"), rs.getLong("defaulted"),
                        rs.getLong("outstanding"), rs.getLong("par30")})
                .single();
        long collected = jdbc.sql("""
                        SELECT coalesce(sum(r.amount_minor), 0) FROM lending_repayment r
                        JOIN lending_loan l ON l.id = r.loan_id WHERE true %s
                        """.formatted(agentFilter))
                .paramSource(params(cutoff, agentIds)).query(Long.class).single();
        long commission = commissionForUser == null ? 0 : jdbc.sql(
                        "SELECT coalesce(sum(amount_minor), 0) FROM lending_commission WHERE agent_user_id = :u")
                .param("u", commissionForUser).query(Long.class).single();
        double ratio = row[2] == 0 ? 0 : Math.round(10_000.0 * row[3] / row[2]) / 10_000.0;
        return new Portfolio((int) row[0], fmt(row[2]), fmt(row[3]), ratio, (int) row[1], fmt(collected), fmt(commission));
    }

    private static org.springframework.jdbc.core.namedparam.MapSqlParameterSource params(LocalDate cutoff,
                                                                                    Collection<UUID> agentIds) {
        var p = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("cutoff", Date.valueOf(cutoff));
        if (agentIds != null) {
            p.addValue("agents", agentIds);
        }
        return p;
    }

    private String currencyOf(UUID loanId) {
        return jdbc.sql("SELECT currency FROM lending_loan WHERE id = :id").param("id", loanId).query(String.class).single();
    }

    private List<InstallmentView> schedule(UUID loanId, String cur) {
        return jdbc.sql("""
                        SELECT seq, due_date, principal_due + interest_due + fee_due AS due,
                               principal_paid + interest_paid + fee_paid + penalty_paid AS paid,
                               penalty_due, status
                        FROM lending_installment WHERE loan_id = :id ORDER BY seq
                        """)
                .param("id", loanId)
                .query((rs, i) -> new InstallmentView(rs.getInt("seq"), rs.getDate("due_date").toLocalDate(),
                        fmt(rs.getLong("due"), cur), fmt(rs.getLong("paid"), cur), fmt(rs.getLong("penalty_due"), cur),
                        rs.getString("status")))
                .list();
    }

    private LoanSummary summary(ResultSet rs, int row) throws SQLException {
        String cur = rs.getString("currency");
        long total = rs.getLong("principal_minor") + rs.getLong("interest_minor") + rs.getLong("fee_minor");
        Date nextDue = rs.getDate("next_due");
        Date oldestOverdue = rs.getDate("oldest_overdue");
        int dpd = oldestOverdue == null ? 0
                : (int) ChronoUnit.DAYS.between(oldestOverdue.toLocalDate(), LocalDate.now(clock));
        return new LoanSummary(
                rs.getObject("id", UUID.class),
                rs.getString("product"),
                fmt(rs.getLong("principal_minor"), cur),
                fmt(total, cur),
                fmt(rs.getLong("outstanding"), cur),
                rs.getString("status"),
                (Integer) rs.getObject("credit_score"),
                Math.max(dpd, 0),
                nextDue == null ? null : nextDue.toLocalDate(),
                rs.getTimestamp("applied_at").toInstant());
    }

    private String fmt(long minor) {
        return fmt(minor, wallets.defaultCurrency());
    }

    private static String fmt(long minor, String cur) {
        return Money.of(minor, cur).toMajor().toPlainString();
    }
}
