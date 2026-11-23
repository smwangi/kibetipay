package com.samwan.kibetipay.shared.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers telemetry until the surrounding transaction commits, so a log event or counter never
 * reports a state change that was rolled back. Runs immediately when no transaction is active.
 *
 * <p>Telemetry must never fail a payment: an exception from the action is logged and swallowed.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {}

    public static void run(Runnable telemetry) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safely(telemetry);
                }
            });
        } else {
            safely(telemetry);
        }
    }

    private static void safely(Runnable telemetry) {
        try {
            telemetry.run();
        } catch (RuntimeException e) {
            log.warn("Telemetry callback failed; the business operation is unaffected", e);
        }
    }
}
