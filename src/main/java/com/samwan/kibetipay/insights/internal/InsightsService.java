package com.samwan.kibetipay.insights.internal;

import com.samwan.kibetipay.insights.InsightsApi;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.wallet.WalletApi;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class InsightsService implements InsightsApi {

    private static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

    private final JdbcClient jdbc;
    private final WalletApi wallets;

    InsightsService(JdbcClient jdbc, WalletApi wallets) {
        this.jdbc = jdbc;
        this.wallets = wallets;
    }

    @Override
    public SpendingSummary spending(String ownerId, YearMonth month) {
        var from = Timestamp.from(month.atDay(1).atStartOfDay(ZONE).toInstant());
        var to = Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant());

        List<CategoryTotal> byCategory = jdbc.sql("""
                        SELECT coalesce(category, 'UNCATEGORISED') AS category, sum(amount_minor) AS total, count(*) AS cnt
                        FROM insight_transaction
                        WHERE owner_id = :o AND direction = 'OUT' AND occurred_at >= :from AND occurred_at < :to
                        GROUP BY 1 ORDER BY 2 DESC
                        """)
                .param("o", ownerId).param("from", from).param("to", to)
                .query((rs, i) -> new CategoryTotal(
                        Category.valueOf(rs.getString("category")),
                        fmt(rs.getLong("total")),
                        rs.getInt("cnt")))
                .list();

        long out = sum(ownerId, InsightDirection.OUT, from, to);
        long in = sum(ownerId, InsightDirection.IN, from, to);
        return new SpendingSummary(ownerId, month, wallets.defaultCurrency(), fmt(out), fmt(in), byCategory);
    }

    private long sum(String owner, InsightDirection dir, Timestamp from, Timestamp to) {
        return jdbc.sql("""
                        SELECT coalesce(sum(amount_minor), 0) FROM insight_transaction
                        WHERE owner_id = :o AND direction = :d AND occurred_at >= :from AND occurred_at < :to
                        """)
                .param("o", owner).param("d", dir.name()).param("from", from).param("to", to)
                .query(Long.class)
                .single();
    }

    private String fmt(long minor) {
        return Money.of(minor, wallets.defaultCurrency()).toMajor().toPlainString();
    }
}
