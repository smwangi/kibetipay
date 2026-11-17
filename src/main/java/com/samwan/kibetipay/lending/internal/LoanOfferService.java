package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.LendingEvents.LoanApplied;
import com.samwan.kibetipay.lending.LendingEvents.LoanDeclined;
import com.samwan.kibetipay.lending.internal.calc.ScheduleCalculator;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LoanOfferService {
    private static final LocalDate BASE = LocalDate.of(2000, 1, 1);
    private final LoanRepository loans;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    LoanOfferService(LoanRepository loans, ApplicationEventPublisher events, Clock clock) {
        this.loans = loans;
        this.events = events;
        this.clock = clock;
    }

    record Instalment(int sequence, long daysAfterDisbursement, String principal, String interest,
                      String fee, String total) {}
    record Offer(UUID loanId, String status, String productCode, String currency, String principal,
                 String interest, String fee, String total, int interestBps, int feeBps, int tenorDays,
                 int penaltyBpsPerDay, List<Instalment> schedule, String decision, Instant decidedAt) {}
    record Response(UUID loanId, String decision, Instant decidedAt, boolean replayed) {}

    @Transactional(readOnly = true)
    Offer offer(UUID id, String borrower) {
        Loan loan = loans.findById(id).orElseThrow(() -> notFound(id));
        if (!loan.borrowerId().equals(borrower)) throw notFound(id);
        var terms = loan.offeredTerms();
        String cur = loan.currency();
        var lines = ScheduleCalculator.schedule(terms, BASE).stream().map(line -> new Instalment(line.seq(),
                ChronoUnit.DAYS.between(BASE, line.dueDate()), fmt(line.principal(), cur), fmt(line.interest(), cur),
                fmt(line.fee(), cur), fmt(line.total(), cur))).toList();
        long interest = ScheduleCalculator.interestFor(terms);
        long fee = ScheduleCalculator.feeFor(terms);
        return new Offer(id, loan.status().name(), loan.productCode(), cur, fmt(terms.principal(), cur),
                fmt(interest, cur), fmt(fee, cur), fmt(Math.addExact(Math.addExact(terms.principal(), interest), fee), cur),
                terms.interestBps(), terms.feeBps(), terms.tenorDays(), loan.penaltyBpsPerDay(), lines,
                loan.borrowerDecision(), loan.borrowerDecidedAt());
    }

    @Transactional
    Response respond(UUID id, String borrower, boolean accept) {
        Loan loan = loans.findForUpdate(id).orElseThrow(() -> notFound(id));
        if (!loan.borrowerId().equals(borrower)) throw notFound(id);
        Instant now = Instant.now(clock);
        boolean changed = loan.borrowerRespond(accept, now);
        if (changed && accept) events.publishEvent(new LoanApplied(id));
        if (changed && !accept) events.publishEvent(new LoanDeclined(id, borrower, "borrower_declined", now));
        return new Response(id, loan.borrowerDecision(), loan.borrowerDecidedAt(), !changed);
    }

    private static DomainException.NotFound notFound(UUID id) {
        return new DomainException.NotFound("loan_not_found", "No loan " + id);
    }

    private static String fmt(long amount, String currency) {
        return Money.of(amount, currency).toMajor().toPlainString();
    }
}
