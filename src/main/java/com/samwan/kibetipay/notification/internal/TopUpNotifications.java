package com.samwan.kibetipay.notification.internal;

import com.samwan.kibetipay.mobilemoney.MobileMoneyEvents.TopUpSettled;
import com.samwan.kibetipay.shared.id.Ids;
import com.samwan.kibetipay.shared.money.Money;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells the customer how an M-Pesa top-up ended. A failed top-up leaves no ledger entry, so
 * without this message it would show nowhere once the customer left the top-up screen.
 */
@Component
class TopUpNotifications {

    private final JdbcClient jdbc;

    TopUpNotifications(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener
    void settled(TopUpSettled e) {
        String amount = Money.of(e.amountMinor(), e.currency()).toString();
        String kind = e.success() ? "TOPUP_COMPLETED" : "TOPUP_FAILED";
        String message = e.success()
                ? "Your M-Pesa top-up of " + amount + " has been added to your wallet."
                : "Your M-Pesa top-up of " + amount + " did not go through. No money was added to your wallet.";
        jdbc.sql("""
                INSERT INTO notification_message (id, recipient_id, event_key, kind, message, created_at)
                VALUES (:id, :recipient, :key, :kind, :message, :at)
                ON CONFLICT (event_key) DO NOTHING
                """).param("id", Ids.newId()).param("recipient", e.ownerId())
                .param("key", "topup:" + e.collectionId()).param("kind", kind).param("message", message)
                .param("at", Timestamp.from(e.at())).update();
    }
}
