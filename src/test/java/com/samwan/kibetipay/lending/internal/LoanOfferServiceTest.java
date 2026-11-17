package com.samwan.kibetipay.lending.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.samwan.kibetipay.lending.LendingEvents.LoanApplied;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class LoanOfferServiceTest {
    @Test
    void onlyBorrowerCanAcceptAndAcceptancePublishesOnce() {
        var repo = mock(LoanRepository.class);
        var events = mock(ApplicationEventPublisher.class);
        var clock = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);
        var service = new LoanOfferService(repo, events, clock);
        var loan = Loan.apply("alice", UUID.randomUUID(), "agent",
                new Product("P", "Product", 1, 1000000, 1000000, 1000, 200, 28, 4, 10, true),
                Money.of(100000, "KES"), "key");
        when(repo.findForUpdate(loan.id())).thenReturn(Optional.of(loan));
        when(repo.findById(loan.id())).thenReturn(Optional.of(loan));
        assertThatThrownBy(() -> service.offer(loan.id(), "bob")).isInstanceOf(DomainException.NotFound.class);
        assertThatThrownBy(() -> service.respond(loan.id(), "bob", true)).isInstanceOf(DomainException.NotFound.class);
        verifyNoInteractions(events);
        var offer = service.offer(loan.id(), "alice");
        assertThat(offer.total()).isEqualTo("1120.00");
        assertThat(offer.schedule()).hasSize(4);
        assertThat(offer.schedule().getLast().daysAfterDisbursement()).isEqualTo(28);
        assertThat(service.respond(loan.id(), "alice", true).replayed()).isFalse();
        assertThat(service.respond(loan.id(), "alice", true).replayed()).isTrue();
        verify(events, times(1)).publishEvent(new LoanApplied(loan.id()));
    }
}
