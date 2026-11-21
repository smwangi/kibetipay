package com.samwan.kibetipay.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class AfterCommitTest {

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runsImmediatelyWithoutTransaction() {
        var calls = new AtomicInteger();
        AfterCommit.run(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
    }

    @Test
    void waitsForCommit() {
        var calls = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(calls::incrementAndGet);
        assertThat(calls).hasValue(0);

        TransactionSynchronizationUtils.triggerAfterCommit();
        assertThat(calls).hasValue(1);
    }

    @Test
    void neverReportsARolledBackChange() {
        var calls = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(calls::incrementAndGet);

        TransactionSynchronizationUtils.invokeAfterCompletion(
                TransactionSynchronizationManager.getSynchronizations(), TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(calls).hasValue(0);
    }

    @Test
    void telemetryFailureDoesNotPropagate() {
        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.run(() -> {
            throw new IllegalStateException("exporter down");
        });
        TransactionSynchronizationUtils.triggerAfterCommit();   // must not throw
    }
}
