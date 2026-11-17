package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.shared.error.DomainException;

/** State rules for collection results; provider submission is not settlement. */
final class CollectionState {
    enum Status {
        SUBMITTING,
        PENDING,
        UNKNOWN,
        REJECTED,
        COMPLETED,
        FAILED
    }
    private CollectionState() {}

    static boolean requireResult(Status status, boolean success) {
        if ((status == Status.COMPLETED && success) || (status == Status.FAILED && !success)) return false;
        if (status != Status.PENDING && status != Status.UNKNOWN) {
            throw new DomainException.Conflict("invalid_collection_state", "Collection is " + status);
        }
        return true;
    }
}
