package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.shared.id.Ids;
import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.shared.error.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only evidence import and exception report; no provider or ledger side effects (see SuspenseController). */
@RestController
class ReconciliationController {
    private final JdbcClient jdbc;
    ReconciliationController(JdbcClient jdbc) { this.jdbc = jdbc; }

    record Expected(@NotBlank @Size(max=40) String provider, @NotBlank @Size(max=100) String reference,
                    @NotNull Direction direction, @Positive long amountMinor,
                    @NotBlank @Pattern(regexp="[A-Z]{3}") String currency) {}
    record ProviderTransaction(@NotBlank @Size(max=40) String provider,
                    @NotBlank @Size(max=100) String providerReference, @Size(max=100) String expectedReference,
                    @NotNull Direction direction, @Positive long amountMinor,
                    @NotBlank @Pattern(regexp="[A-Z]{3}") String currency, @NotNull Instant occurredAt) {}
    enum Direction {
        COLLECTION,
        PAYOUT
    }
    record Imported(UUID id) {}
    record Comparison(UUID expectedId, String provider, String reference, UUID transactionId,
                      String providerReference, String result, UUID suspenseItemId, String suspenseStatus) {}

    @PostMapping("/api/admin/mobile-money/expected-payments")
    Imported expected(@Valid @RequestBody Expected e) {
        UUID id = Ids.newId();
        try { jdbc.sql("""
                INSERT INTO mobilemoney_expected_payment (id, provider, reference, direction, amount_minor, currency, created_by)
                VALUES (:id, :provider, :reference, :direction, :amount, :currency, :user)
                """).param("id", id).param("provider", e.provider()).param("reference", e.reference())
                .param("direction", e.direction().name()).param("amount", e.amountMinor())
                .param("currency", e.currency()).param("user", CurrentUser.id()).update();
        } catch (DuplicateKeyException ex) {
            throw new DomainException.Conflict("expected_payment_exists", "Provider and expected reference already exist");
        }
        return new Imported(id);
    }

    @PostMapping("/api/admin/mobile-money/provider-transactions")
    Imported transaction(@Valid @RequestBody ProviderTransaction p) {
        UUID id = Ids.newId();
        try { jdbc.sql("""
                INSERT INTO mobilemoney_provider_transaction
                    (id, provider, provider_reference, expected_reference, direction, amount_minor, currency, occurred_at, imported_by)
                VALUES (:id, :provider, :providerReference, :expectedReference, :direction, :amount, :currency, :at, :user)
                """).param("id", id).param("provider", p.provider()).param("providerReference", p.providerReference())
                .param("expectedReference", p.expectedReference()).param("direction", p.direction().name())
                .param("amount", p.amountMinor()).param("currency", p.currency())
                .param("at", java.sql.Timestamp.from(p.occurredAt())).param("user", CurrentUser.id()).update();
        } catch (DuplicateKeyException ex) {
            throw new DomainException.Conflict("provider_transaction_exists", "Provider transaction reference already exists");
        }
        return new Imported(id);
    }

    @GetMapping("/api/admin/mobile-money/reconciliation")
    List<Comparison> report() {
        return jdbc.sql("""
                SELECT e.id AS expected_id, coalesce(e.provider, p.provider) AS provider,
                       coalesce(e.reference, p.expected_reference) AS reference, p.id AS transaction_id, p.provider_reference,
                       e.direction AS expected_direction, e.amount_minor AS expected_amount, e.currency AS expected_currency,
                       p.direction AS actual_direction, p.amount_minor AS actual_amount, p.currency AS actual_currency,
                       count(p.id) OVER (PARTITION BY e.id) AS matches,
                       s.id AS suspense_id, s.status AS suspense_status
                FROM mobilemoney_expected_payment e FULL JOIN mobilemoney_provider_transaction p
                    ON e.provider = p.provider AND e.reference = p.expected_reference
                LEFT JOIN mobilemoney_suspense_item s ON s.provider_transaction_id = p.id
                ORDER BY coalesce(p.imported_at, e.created_at) DESC LIMIT 500
                """).query((rs, n) -> {
                    UUID expectedId = rs.getObject("expected_id", UUID.class);
                    UUID transactionId = rs.getObject("transaction_id", UUID.class);
                    String result = expectedId == null ? "UNMATCHED_PROVIDER" : transactionId == null ? "MISSING_PROVIDER"
                            : rs.getInt("matches") > 1 ? "MULTIPLE_PROVIDER_TRANSACTIONS"
                            : Reconciliation.compare(rs.getString("expected_direction"), rs.getLong("expected_amount"),
                                    rs.getString("expected_currency"), rs.getString("actual_direction"),
                                    rs.getLong("actual_amount"), rs.getString("actual_currency")).name();
                    return new Comparison(expectedId, rs.getString("provider"), rs.getString("reference"),
                            transactionId, rs.getString("provider_reference"), result,
                            rs.getObject("suspense_id", UUID.class), rs.getString("suspense_status"));
                }).list();
    }
}
