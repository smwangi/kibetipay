package com.samwan.kibetipay.lending.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoanConsentTest {
    private final Instant decidedAt = Instant.parse("2026-01-01T10:00:00Z");
    private Loan offered() {
        return Loan.apply("borrower", UUID.randomUUID(), "agent",
                new Product("P", "Product", 1, 1000000, 1000000, 1000, 200, 28, 4, 10, true),
                Money.of(100000, "KES"), "key");
    }

    @Test
    void offerCannotDisburseBeforeBorrowerAcceptsAndRetryIsIdempotent() {
        var loan = offered();
        assertThat(loan.status()).isEqualTo(Loan.Status.OFFERED);
        assertThatThrownBy(() -> loan.activate(UUID.randomUUID(), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(DomainException.Conflict.class);
        assertThat(loan.borrowerRespond(true, decidedAt)).isTrue();
        assertThat(loan.status()).isEqualTo(Loan.Status.APPLIED);
        assertThat(loan.borrowerRespond(true, decidedAt.plusSeconds(10))).isFalse();
        assertThat(loan.borrowerDecidedAt()).isEqualTo(decidedAt);
        assertThatThrownBy(() -> loan.borrowerRespond(false, decidedAt)).isInstanceOf(DomainException.Conflict.class);
    }

    @Test
    void declinedOfferCannotBeAcceptedOrDisbursed() {
        var loan = offered();
        assertThat(loan.borrowerRespond(false, decidedAt)).isTrue();
        assertThat(loan.borrowerRespond(false, decidedAt)).isFalse();
        assertThat(loan.status()).isEqualTo(Loan.Status.DECLINED);
        assertThatThrownBy(() -> loan.borrowerRespond(true, decidedAt)).isInstanceOf(DomainException.Conflict.class);
        assertThatThrownBy(() -> loan.activate(UUID.randomUUID(), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(DomainException.Conflict.class);
    }

    @Test
    void disbursementUsesTheOfferedTerms() {
        var loan = offered();
        var terms = loan.offeredTerms();
        loan.borrowerRespond(true, decidedAt);
        loan.activate(UUID.randomUUID(), LocalDate.of(2026, 1, 1));
        assertThat(loan.outstanding()).isEqualTo(112000);
        assertThat(terms.tenorDays()).isEqualTo(28);
        assertThat(loan.penaltyBpsPerDay()).isEqualTo(10);
    }
}
