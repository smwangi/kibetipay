package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.wallet.WalletApi;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Transparent scorecard (0-100) built from the borrower's own history on the platform.
 * Every point has a reason code, which is what regulators and borrowers are owed
 * ("why was I declined?").
 *
 * <p>Phase 5 (MSc tie-in): train a gradient-boosted model on repayment outcomes with these
 * same features plus wallet cash-flow features, explain each decision with SHAP values, check
 * fairness across regions/agents, and serve it behind this class's interface.
 */
class ScorecardCreditModel implements CreditModel {

    private final JdbcClient jdbc;
    private final WalletApi wallets;

    ScorecardCreditModel(JdbcClient jdbc, WalletApi wallets) {
        this.jdbc = jdbc;
        this.wallets = wallets;
    }

    @Override
    public String name() {
        return "scorecard-v1";
    }

    @Override
    public Score score(String borrowerId, Product product, long principalMinor) {
        var history = jdbc.sql("""
                        SELECT
                          count(*) FILTER (WHERE status = 'REPAID')    AS repaid,
                          count(*) FILTER (WHERE status = 'DEFAULTED') AS defaulted,
                          coalesce(max(principal_minor) FILTER (WHERE status = 'REPAID'), 0) AS largest_repaid
                        FROM lending_loan WHERE borrower_id = :b
                        """)
                .param("b", borrowerId)
                .query((rs, i) -> new long[] {rs.getLong("repaid"), rs.getLong("defaulted"), rs.getLong("largest_repaid")})
                .single();
        long repaid = history[0], defaulted = history[1], largestRepaid = history[2];

        int score = 50;
        List<String> reasons = new ArrayList<>();
        boolean hardDecline = false;

        if (defaulted > 0) {
            score -= 45;
            reasons.add("prior_default");
            hardDecline = true;
        }
        if (repaid > 0) {
            int bonus = (int) Math.min(30, repaid * 10);
            score += bonus;
            reasons.add("repaid_loans:" + repaid);
        }

        // Loan ladder: first loan capped; afterwards at most 2x the largest loan repaid so far.
        long ladderCap = repaid == 0 ? product.firstLoanMaxMinor() : Math.max(product.firstLoanMaxMinor(), largestRepaid * 2);
        if (principalMinor > ladderCap) {
            reasons.add("above_ladder_limit:" + ladderCap);
            hardDecline = true;
        }

        if (wallets.exists(borrowerId, wallets.defaultCurrency())) {
            long balance = wallets.get(borrowerId, wallets.defaultCurrency()).balance().minor();
            if (balance * 10 >= principalMinor) {      // savings >= 10% of the loan
                score += 10;
                reasons.add("savings_buffer");
            }
        } else {
            reasons.add("no_wallet");
            hardDecline = true;
        }
        return new Score(Math.max(0, Math.min(100, score)), List.copyOf(reasons), hardDecline);
    }
}
