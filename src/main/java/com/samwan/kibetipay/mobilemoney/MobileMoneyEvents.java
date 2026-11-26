package com.samwan.kibetipay.mobilemoney;

import java.time.Instant;
import java.util.UUID;

/**
 * Published integration events of the mobile money module. Primitives and ids only, so they can
 * be externalized unchanged.
 */
public final class MobileMoneyEvents {

    private MobileMoneyEvents() {
    }

    /**
     * The provider reported the final result of a customer top-up (collection). Published in the
     * same transaction that records the result (and, on success, credits the wallet), so it is
     * delivered exactly when the outcome is durable.
     */
    public record TopUpSettled(UUID collectionId, String ownerId, long amountMinor, String currency,
                               boolean success, Instant at) {}
}
