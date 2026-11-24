package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.mobilemoney.MobileMoneyGateway;
import com.samwan.kibetipay.shared.observability.AfterCommit;
import com.samwan.kibetipay.shared.observability.TraceOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * Telemetry for provider integrations (M-Pesa and the simulator): external call latency and
 * outcome, collection lifecycle events and suspense handling.
 *
 * <p>Phone numbers are never logged or tagged. The client's raw Idempotency-Key is not logged
 * either; collections are identified by their internal id. Tags use fixed vocabularies
 * (provider name, operation, outcome).
 */
@Component
class MobileMoneyTelemetry {

    private static final Logger log = LoggerFactory.getLogger("kibetipay.mobilemoney.events");

    /** How an external provider call ended, from KibetiPay's point of view. */
    enum CallOutcome {
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        /** The provider answered, but not with something we can trust. Outcome unknown. */
        INVALID_RESPONSE("invalid_response"),
        /** Timeout or transport failure. The provider may or may not have acted. Outcome unknown. */
        ERROR("error");

        final String tag;

        CallOutcome(String tag) {
            this.tag = tag;
        }
    }

    private final MeterRegistry metrics;
    private final TraceOutcome trace;

    MobileMoneyTelemetry(MeterRegistry metrics, TraceOutcome trace) {
        this.metrics = metrics;
        this.trace = trace;
    }

    /**
     * Times one call to the provider. The outcome is decided by the caller after inspecting the
     * response, so a successful HTTP call with a bad payload is still recorded as unknown.
     */
    <T> T timeCall(String provider, String operation, Supplier<T> call, java.util.function.Function<T, CallOutcome> classify) {
        Timer.Sample sample = Timer.start(metrics);
        CallOutcome outcome = CallOutcome.ERROR;
        try {
            T response = call.get();
            outcome = classify.apply(response);
            return response;
        } finally {
            sample.stop(metrics.timer("kibetipay.external.request.duration",
                    "provider", provider, "operation", operation, "outcome", outcome.tag));
        }
    }

    // ---- collections (customer pays in via STK push) ----

    void collectionSubmitted(UUID collectionId, String provider, long amountMinor, String currency, CallOutcome outcome) {
        switch (outcome) {
            case ACCEPTED -> { }
            case REJECTED -> trace.mark("rejected");
            case INVALID_RESPONSE, ERROR -> trace.mark("unknown");
        }
        var builder = switch (outcome) {
            case ACCEPTED, REJECTED -> log.atInfo();
            // SUBMITTING was committed before the call; the provider may have charged the customer.
            case INVALID_RESPONSE, ERROR -> log.atWarn();
        };
        event(builder, "collection.submitted", collectionId)
                .addKeyValue("provider", provider)
                .addKeyValue("outcome", outcome.tag)
                .addKeyValue("amountMinor", amountMinor)
                .addKeyValue("currency", currency)
                .log(outcome == CallOutcome.ACCEPTED || outcome == CallOutcome.REJECTED
                        ? "Collection {} submitted to " + provider + ": " + outcome.tag
                        : "Collection {} outcome UNKNOWN after provider call; investigate, do not resubmit", collectionId);
        metrics.counter("kibetipay.mobilemoney.collections", "provider", provider, "result", outcome.tag).increment();
    }

    void collectionReplayed(UUID collectionId, String provider) {
        event(log.atInfo(), "collection.replayed", collectionId)
                .addKeyValue("provider", provider)
                .log("Collection {} replayed for a repeated Idempotency-Key", collectionId);
        metrics.counter("kibetipay.idempotency.replays", "scope", "collection").increment();
    }

    void collectionConflict(UUID collectionId) {
        trace.mark("rejected");
        event(log.atWarn(), "collection.idempotency.conflict", collectionId)
                .log("Idempotency-Key reused with a different collection (original {})", collectionId);
        metrics.counter("kibetipay.mobilemoney.collections", "provider", "n/a", "result", "idempotency_conflict")
                .increment();
    }

    /** The provider's final result was applied. Reported after commit. */
    void collectionSettled(UUID collectionId, String provider, boolean success, UUID journalId, long amountMinor,
                           String currency) {
        if (!success) {
            trace.mark("failed");
        }
        AfterCommit.run(() -> {
            event(success ? log.atInfo() : log.atWarn(), success ? "collection.completed" : "collection.failed",
                    collectionId)
                    .addKeyValue("provider", provider)
                    .addKeyValue("journalId", journalId)
                    .addKeyValue("amountMinor", amountMinor)
                    .addKeyValue("currency", currency)
                    .log("Collection {} {}", collectionId, success ? "completed; wallet credited" : "failed at provider");
            metrics.counter("kibetipay.mobilemoney.collections", "provider", provider,
                    "result", success ? "completed" : "failed").increment();
        });
    }

    /** A provider result that does not match what we sent. Possible fraud or integration bug. */
    void collectionResultMismatch(UUID collectionId, String code) {
        trace.mark("rejected");
        event(log.atWarn(), "collection.result.mismatch", collectionId)
                .addKeyValue("reason", code)
                .log("Provider result for collection {} rejected: {}", collectionId, code);
        metrics.counter("kibetipay.mobilemoney.collections", "provider", "n/a", "result", "result_mismatch")
                .increment();
    }

    // ---- suspense ----

    void suspenseOpened(UUID itemId, UUID providerTransactionId, long amountMinor, String currency) {
        trace.mark("review");
        AfterCommit.run(() -> {
            log.atWarn()
                    .addKeyValue("event", "suspense.opened")
                    .addKeyValue("suspenseItemId", itemId)
                    .addKeyValue("providerTransactionId", providerTransactionId)
                    .addKeyValue("amountMinor", amountMinor)
                    .addKeyValue("currency", currency)
                    .log("Unattributed collection booked to suspense as item {}", itemId);
            metrics.counter("kibetipay.mobilemoney.suspense", "action", "opened").increment();
        });
    }

    void suspenseAllocated(UUID itemId, UUID journalId, long amountMinor, String currency) {
        AfterCommit.run(() -> {
            log.atInfo()
                    .addKeyValue("event", "suspense.allocated")
                    .addKeyValue("suspenseItemId", itemId)
                    .addKeyValue("journalId", journalId)
                    .addKeyValue("amountMinor", amountMinor)
                    .addKeyValue("currency", currency)
                    .log("Suspense item {} allocated to a customer wallet", itemId);
            metrics.counter("kibetipay.mobilemoney.suspense", "action", "allocated").increment();
        });
    }

    static CallOutcome classify(MobileMoneyGateway gateway, MobileMoneyGateway.Requested response) {
        if (response == null || !gateway.provider().equals(response.provider()) || response.providerRequestId() == null
                || response.providerRequestId().isBlank() || response.status() == null) {
            return CallOutcome.INVALID_RESPONSE;
        }
        return response.status() == MobileMoneyGateway.Status.ACCEPTED ? CallOutcome.ACCEPTED : CallOutcome.REJECTED;
    }

    private static LoggingEventBuilder event(LoggingEventBuilder builder, String event, UUID collectionId) {
        return builder.addKeyValue("event", event).addKeyValue("collectionId", collectionId);
    }
}
