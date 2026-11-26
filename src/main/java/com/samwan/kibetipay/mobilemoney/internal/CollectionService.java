package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.shared.id.Ids;
import com.samwan.kibetipay.mobilemoney.MobileMoneyEvents.TopUpSettled;
import com.samwan.kibetipay.mobilemoney.MobileMoneyGateway;
import com.samwan.kibetipay.mobilemoney.internal.CollectionState.Status;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.paging.Cursor;
import com.samwan.kibetipay.shared.paging.Page;
import com.samwan.kibetipay.wallet.WalletApi;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class CollectionService {
    record Collection(UUID id, String owner, String key, String provider, String providerRequestId,
                      String msisdn, long amountMinor, String currency, Status status,
                      String providerReceipt, UUID journalId, Instant createdAt, Instant updatedAt) {}
    record Result(UUID id, String status, String provider, String providerRequestId, String receipt,
                  UUID journalId, boolean replayed) {}

    /** A customer's own top-up, for their history. The phone number is masked. */
    record CollectionView(UUID id, String status, Money amount, String msisdn, String provider, String receipt,
                          Instant createdAt, Instant updatedAt) {}
    private final JdbcClient jdbc;
    private final MobileMoneyGateway gateway;
    private final WalletApi wallets;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MobileMoneyTelemetry telemetry;
    private final ApplicationEventPublisher events;

    CollectionService(JdbcClient jdbc, MobileMoneyGateway gateway, WalletApi wallets, TransactionTemplate tx, Clock clock,
                      MobileMoneyTelemetry telemetry, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.wallets = wallets;
        this.tx = tx;
        this.clock = clock;
        this.telemetry = telemetry;
        this.events = events;
    }

    Result initiate(String owner, String key, String msisdn, Money amount) {
        var request = new MobileMoneyGateway.CollectionRequest(Ids.newId().toString(), msisdn, amount,
                "wallet", "Wallet deposit");
        if (!wallets.exists(owner, amount.currencyCode())) {
            throw new WalletApi.WalletNotFoundException(owner, amount.currencyCode());
        }
        UUID id = UUID.fromString(request.idempotencyKey());
        int inserted = tx.execute(s -> jdbc.sql("""
                INSERT INTO mobilemoney_collection (id, owner_id, idempotency_key, provider, msisdn,
                  amount_minor, currency, status, created_at, updated_at)
                VALUES (:id, :owner, :key, :provider, :msisdn, :amount, :currency, 'SUBMITTING', :now, :now)
                ON CONFLICT (owner_id, idempotency_key) DO NOTHING
                """).param("id", id).param("owner", owner).param("key", key).param("provider", gateway.provider())
                .param("msisdn", msisdn).param("amount", amount.minor()).param("currency", amount.currencyCode())
                .param("now", Timestamp.from(Instant.now(clock))).update());
        if (inserted == 0) {
            Collection existing = byKey(owner, key);
            if (!existing.msisdn().equals(msisdn) || existing.amountMinor() != amount.minor()
                    || !existing.currency().equals(amount.currencyCode())) {
                telemetry.collectionConflict(existing.id());
                throw new DomainException.Conflict("idempotency_key_reused", "Key was used for a different collection");
            }
            telemetry.collectionReplayed(existing.id(), existing.provider());
            return result(existing, true);
        }

        // SUBMITTING is committed before the external call. A crash or timeout is ambiguous:
        // never automatically issue a second request; operations must investigate.
        var outcome = MobileMoneyTelemetry.CallOutcome.ERROR;
        try {
            var response = telemetry.timeCall(gateway.provider(), "collection",
                    () -> gateway.requestCollection(request), r -> MobileMoneyTelemetry.classify(gateway, r));
            outcome = MobileMoneyTelemetry.classify(gateway, response);
            if (outcome == MobileMoneyTelemetry.CallOutcome.INVALID_RESPONSE) {
                markUnknown(id);
            } else {
                String status = (response.status() == MobileMoneyGateway.Status.ACCEPTED ? Status.PENDING : Status.REJECTED).name();
                tx.executeWithoutResult(s -> jdbc.sql("""
                        UPDATE mobilemoney_collection SET status = :status, provider_request_id = :providerId, updated_at = :now
                        WHERE id = :id AND status = 'SUBMITTING'
                        """).param("status", status).param("providerId", response.providerRequestId())
                        .param("now", Timestamp.from(Instant.now(clock))).param("id", id).update());
            }
        } catch (RuntimeException e) {
            markUnknown(id);
        }
        telemetry.collectionSubmitted(id, gateway.provider(), amount.minor(), amount.currencyCode(), outcome);
        return result(byId(id, owner, false), false);
    }

    Result get(UUID id, String owner) { return result(byId(id, owner, false), false); }

    /** The owner's top-ups, newest first, including pending, rejected and failed ones. */
    Page<CollectionView> history(String owner, Page.Query q) {
        var sql = new StringBuilder("SELECT * FROM mobilemoney_collection WHERE owner_id = :owner");
        if (q.from() != null) {
            sql.append(" AND created_at >= :from");
        }
        if (q.toExclusive() != null) {
            sql.append(" AND created_at < :to");
        }
        if (q.before() != null) {
            sql.append(" AND (created_at, id) < (:beforeAt, :beforeId)");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("owner", owner).param("limit", q.limit() + 1);
        if (q.from() != null) {
            spec = spec.param("from", Timestamp.from(q.from()));
        }
        if (q.toExclusive() != null) {
            spec = spec.param("to", Timestamp.from(q.toExclusive()));
        }
        if (q.before() != null) {
            spec = spec.param("beforeAt", Timestamp.from(q.before().at())).param("beforeId", q.before().id());
        }
        return Page.fromOverfetch(spec.query(this::map).list(), q.limit(), c -> new Cursor(c.createdAt(), c.id()))
                .map(CollectionService::view);
    }

    /** Trusted adapter entry point. A real adapter must authenticate and validate the provider before calling. */
    Result complete(UUID id, String providerRequestId, long amountMinor, String currency, boolean success, String receipt) {
        return tx.execute(s -> {
            Collection c = byId(id, null, true);
            if (!c.provider().equals(gateway.provider()) || c.providerRequestId() == null
                    || !c.providerRequestId().equals(providerRequestId) || c.amountMinor() != amountMinor
                    || !c.currency().equals(currency)) {
                telemetry.collectionResultMismatch(id, "collection_result_mismatch");
                throw new DomainException.Conflict("collection_result_mismatch", "Result does not match the collection");
            }
            if (success && (receipt == null || receipt.isBlank())) {
                throw new DomainException.RuleViolation("missing_receipt", "A successful collection requires a provider receipt");
            }
            boolean changed = CollectionState.requireResult(c.status(), success);
            if (!changed) {
                if (success && !receipt.equals(c.providerReceipt())) {
                    telemetry.collectionResultMismatch(id, "receipt_differs");
                    throw new DomainException.Conflict("collection_result_mismatch", "Receipt differs from the recorded result");
                }
                return result(c, true);
            }
            UUID journalId = success ? wallets.deposit(c.owner(), Money.of(c.amountMinor(), c.currency()),
                    "mobilemoney:collection:" + id).journalId() : null;
            jdbc.sql("""
                    UPDATE mobilemoney_collection SET status = :status, provider_receipt = :receipt,
                      journal_id = :journal, updated_at = :now WHERE id = :id
                    """).param("status", (success ? Status.COMPLETED : Status.FAILED).name()).param("receipt", success ? receipt : null)
                    .param("journal", journalId).param("now", Timestamp.from(Instant.now(clock))).param("id", id).update();
            telemetry.collectionSettled(id, c.provider(), success, journalId, c.amountMinor(), c.currency());
            events.publishEvent(new TopUpSettled(id, c.owner(), c.amountMinor(), c.currency(), success, Instant.now(clock)));
            return result(byId(id, null, false), false);
        });
    }

    private void markUnknown(UUID id) {
        tx.executeWithoutResult(s -> jdbc.sql("UPDATE mobilemoney_collection SET status = 'UNKNOWN', updated_at = :now WHERE id = :id AND status = 'SUBMITTING'")
                .param("now", Timestamp.from(Instant.now(clock))).param("id", id).update());
    }

    private Collection byKey(String owner, String key) {
        return jdbc.sql("SELECT * FROM mobilemoney_collection WHERE owner_id = :owner AND idempotency_key = :key")
                .param("owner", owner).param("key", key).query(this::map).single();
    }

    private Collection byId(UUID id, String owner, boolean lock) {
        String sql = "SELECT * FROM mobilemoney_collection WHERE id = :id" + (owner == null ? "" : " AND owner_id = :owner")
                + (lock ? " FOR UPDATE" : "");
        var query = jdbc.sql(sql).param("id", id);
        if (owner != null) {
            query = query.param("owner", owner);
        }
        return query.query(this::map).optional().orElseThrow(() -> new DomainException.NotFound("collection_not_found", "No collection " + id));
    }

    private Collection map(ResultSet r, int n) throws SQLException {
        return new Collection(r.getObject("id", UUID.class), r.getString("owner_id"), r.getString("idempotency_key"),
                r.getString("provider"), r.getString("provider_request_id"), r.getString("msisdn"), r.getLong("amount_minor"),
                r.getString("currency").trim(), Status.valueOf(r.getString("status")), r.getString("provider_receipt"),
                r.getObject("journal_id", UUID.class), r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant());
    }
    private static CollectionView view(Collection c) {
        return new CollectionView(c.id(), c.status().name(), Money.of(c.amountMinor(), c.currency()),
                mask(c.msisdn()), c.provider(), c.providerReceipt(), c.createdAt(), c.updatedAt());
    }

    /** 254712345678 -> 2547••••5678: enough to recognise your own number, not to harvest it. */
    static String mask(String msisdn) {
        if (msisdn == null || msisdn.length() < 8) {
            return msisdn;
        }
        return msisdn.substring(0, 4) + "•".repeat(msisdn.length() - 8) + msisdn.substring(msisdn.length() - 4);
    }

    private static Result result(Collection c, boolean replayed) {
        return new Result(c.id(), c.status().name(), c.provider(), c.providerRequestId(), c.providerReceipt(), c.journalId(), replayed);
    }
}
