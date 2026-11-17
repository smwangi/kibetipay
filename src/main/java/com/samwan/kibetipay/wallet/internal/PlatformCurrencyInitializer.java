package com.samwan.kibetipay.wallet.internal;

import com.samwan.kibetipay.shared.id.Ids;
import com.samwan.kibetipay.wallet.WalletApi.SystemAccounts;
import com.samwan.kibetipay.shared.money.CurrencyRegistry;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Initializes system wallets without converting or relabeling existing balances. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class PlatformCurrencyInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final LedgerProperties properties;
    private final TransactionTemplate transactions;
    private final CurrencyRegistry currencyRegistry;

    PlatformCurrencyInitializer(JdbcClient jdbc, LedgerProperties properties, TransactionTemplate transactions,
                                CurrencyRegistry registry) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.transactions = transactions;
        this.currencyRegistry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> initialize());
    }

    void initialize() {
        String currency = properties.defaultCurrency();
        if (!currencyRegistry.supports(currency)) {
            throw new IllegalStateException("The operating currency must be enabled in the currency registry: " + currency);
        }
        jdbc.sql("INSERT INTO wallet_platform_currency (singleton, currency) VALUES (true, :currency) ON CONFLICT DO NOTHING")
                .param("currency", currency).update();
        String recorded = jdbc.sql("SELECT currency FROM wallet_platform_currency WHERE singleton = true FOR UPDATE")
                .query(String.class).single().trim();
        if (!recorded.equals(currency)) {
            throw new IllegalStateException("The configured currency differs from the established platform currency " + recorded);
        }
        long incompatible = jdbc.sql("""
                SELECT (SELECT count(*) FROM wallet_account WHERE type = 'USER' AND currency <> :currency)
                     + (SELECT count(*) FROM wallet_ledger_entry WHERE currency <> :currency)
                """).param("currency", currency).query(Long.class).single();
        if (incompatible > 0) {
            throw new IllegalStateException("Existing wallet or ledger data uses a different currency; currency conversion is not supported");
        }
        for (var account : List.of(
                new SystemAccount(SystemAccounts.FLOAT, true),
                new SystemAccount(SystemAccounts.LENDING_POOL, false),
                new SystemAccount(SystemAccounts.INTEREST_INCOME, false),
                new SystemAccount(SystemAccounts.FEE_INCOME, false),
                new SystemAccount(SystemAccounts.PENALTY_INCOME, false),
                new SystemAccount(SystemAccounts.COMMISSION_EXPENSE, true))) {
            jdbc.sql("""
                    INSERT INTO wallet_account (id, owner_id, currency, type, status, balance_minor, allow_negative, version, created_at)
                    VALUES (:id, :owner, :currency, 'SYSTEM', 'ACTIVE', 0, :negative, 0, now())
                    ON CONFLICT (owner_id, currency) WHERE type = 'SYSTEM' DO NOTHING
                    """).param("id", Ids.newId()).param("owner", account.owner())
                    .param("currency", currency).param("negative", account.allowNegative()).update();
        }
    }

    private record SystemAccount(String owner, boolean allowNegative) {}
}
