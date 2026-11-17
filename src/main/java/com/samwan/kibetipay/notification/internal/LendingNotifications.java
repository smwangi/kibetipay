package com.samwan.kibetipay.notification.internal;

import com.samwan.kibetipay.lending.LendingEvents.LoanDeclined;
import com.samwan.kibetipay.lending.LendingEvents.LoanDisbursed;
import com.samwan.kibetipay.lending.LendingEvents.LoanRepaid;
import com.samwan.kibetipay.lending.LendingEvents.RepaymentReceived;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class LendingNotifications {
    private final JdbcClient jdbc;

    LendingNotifications(JdbcClient jdbc) { this.jdbc = jdbc; }

    @ApplicationModuleListener
    void disbursed(LoanDisbursed e) {
        insert(e.borrowerId(), "disbursed:" + e.loanId(), "LOAN_DISBURSED",
                "Your loan " + e.loanId() + " has been disbursed to your wallet.", e.at());
    }

    @ApplicationModuleListener
    void declined(LoanDeclined e) {
        insert(e.borrowerId(), "declined:" + e.loanId(), "LOAN_DECLINED",
                "Your loan application " + e.loanId() + " was declined.", e.at());
    }

    @ApplicationModuleListener
    void repayment(RepaymentReceived e) {
        insert(e.borrowerId(), "repayment:" + e.repaymentId(), "REPAYMENT_RECEIVED",
                "Your repayment for loan " + e.loanId() + " was received.", e.at());
    }

    @ApplicationModuleListener
    void repaid(LoanRepaid e) {
        insert(e.borrowerId(), "repaid:" + e.loanId(), "LOAN_REPAID",
                "Your loan " + e.loanId() + " has been fully repaid.", e.at());
    }

    private void insert(String recipient, String key, String kind, String message, Instant at) {
        jdbc.sql("""
                INSERT INTO notification_message (id, recipient_id, event_key, kind, message, created_at)
                VALUES (:id, :recipient, :key, :kind, :message, :at)
                ON CONFLICT (event_key) DO NOTHING
                """).param("id", UUID.randomUUID()).param("recipient", recipient).param("key", key)
                .param("kind", kind).param("message", message).param("at", Timestamp.from(at)).update();
    }
}
