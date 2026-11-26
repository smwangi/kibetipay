package com.samwan.kibetipay.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.transfer.TransferQueries.CustomerTransferView;
import com.samwan.kibetipay.transfer.TransferQueries.TransferView;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerTransferViewTest {

    private static TransferView transfer(String status, Integer score, String reason) {
        return new TransferView(UUID.randomUUID(), "KP7H3QX9M2AB", "alice", "bob", "100.00", "KES", "lunch",
                status, score, reason, Instant.now(), Instant.now());
    }

    @Test
    void customerViewHasNoRiskFields() {
        var names = Arrays.stream(CustomerTransferView.class.getRecordComponents()).map(RecordComponent::getName);
        assertThat(names).doesNotContain("riskScore", "failureReason");
    }

    @Test
    void riskRejectionNeverRevealsWhichRulesFired() {
        var view = CustomerTransferView.of(transfer("REJECTED", 91, "risk_rejected: velocity_exceeded, new_recipient_large"));
        assertThat(view.statusMessage()).contains("declined after a security check")
                .doesNotContain("velocity").doesNotContain("91").doesNotContain("risk_rejected");
    }

    @Test
    void analystNotesStayInternal() {
        var view = CustomerTransferView.of(transfer("REJECTED", null, "declined_by_analyst: matches mule pattern"));
        assertThat(view.statusMessage()).doesNotContain("mule").doesNotContain("analyst");
    }

    @Test
    void ledgerFailuresGetPlainLanguage() {
        assertThat(CustomerTransferView.of(transfer("FAILED", 5, "insufficient_funds")).statusMessage())
                .contains("balance was too low").contains("No money left your wallet");
        assertThat(CustomerTransferView.of(transfer("FAILED", 5, "wallet_frozen")).statusMessage())
                .doesNotContain("frozen").contains("could not be completed");
        assertThat(CustomerTransferView.of(transfer("COMPLETED", 5, null)).statusMessage()).isNull();
    }
}
