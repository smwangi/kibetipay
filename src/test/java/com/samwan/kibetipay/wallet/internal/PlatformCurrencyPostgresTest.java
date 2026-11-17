package com.samwan.kibetipay.wallet.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.samwan.kibetipay.shared.money.CurrencyRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only against an explicitly provided, migrated, disposable PostgreSQL database. */
@EnabledIfSystemProperty(named = "kibetipay.currency.test.jdbc", matches = ".+")
class PlatformCurrencyPostgresTest {
    @Test
    void initializesUsdAndRejectsChangingAnEstablishedBook() {
        var dataSource = new DriverManagerDataSource(System.getProperty("kibetipay.currency.test.jdbc"));
        var jdbc = JdbcClient.create(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var usd = new PlatformCurrencyInitializer(jdbc, new LedgerProperties("USD", null), transactions,
                new CurrencyRegistry(List.of("USD")));
        transactions.executeWithoutResult(s -> usd.initialize());
        transactions.executeWithoutResult(s -> usd.initialize());
        assertThat(jdbc.sql("SELECT count(*) FROM wallet_account WHERE type = 'SYSTEM' AND currency = 'USD'")
                .query(Long.class).single()).isEqualTo(6);
        var tzs = new PlatformCurrencyInitializer(jdbc, new LedgerProperties("TZS", null), transactions,
                new CurrencyRegistry(List.of("TZS")));
        assertThatThrownBy(() -> transactions.executeWithoutResult(s -> tzs.initialize()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT currency FROM wallet_platform_currency WHERE singleton = true")
                .query(String.class).single().trim()).isEqualTo("USD");
    }
}
