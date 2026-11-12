package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.lending.internal.calc.RepaymentAllocator;
import com.samwan.kibetipay.lending.internal.calc.ScheduleCalculator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * {@code @DynamicUpdate}: Hibernate writes only the columns this code changed (the *_paid columns
 * and status). The nightly batch job updates penalty_due with plain SQL; without this annotation a
 * concurrent repayment would write back its stale penalty_due and silently erase the new penalty.
 */
@Entity
@DynamicUpdate
@Table(name = "lending_installment")
class Installment {

    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id")
    private Loan loan;
    @Column(nullable = false)
    private int seq;
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;
    @Column(name = "principal_due", nullable = false)
    private long principalDue;
    @Column(name = "interest_due", nullable = false)
    private long interestDue;
    @Column(name = "fee_due", nullable = false)
    private long feeDue;
    @Column(name = "penalty_due", nullable = false)
    private long penaltyDue;
    @Column(name = "principal_paid", nullable = false)
    private long principalPaid;
    @Column(name = "interest_paid", nullable = false)
    private long interestPaid;
    @Column(name = "fee_paid", nullable = false)
    private long feePaid;
    @Column(name = "penalty_paid", nullable = false)
    private long penaltyPaid;
    enum Status { DUE, OVERDUE, PAID }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    protected Installment() {
    }

    Installment(Loan loan, ScheduleCalculator.Line line) {
        this.id = UUID.randomUUID();
        this.loan = loan;
        this.seq = line.seq();
        this.dueDate = line.dueDate();
        this.principalDue = line.principal();
        this.interestDue = line.interest();
        this.feeDue = line.fee();
        this.status = Status.DUE;
    }

    void pay(RepaymentAllocator.Part part) {
        penaltyPaid += part.penalty();
        feePaid += part.fee();
        interestPaid += part.interest();
        principalPaid += part.principal();
        if (outstanding() < 0) {
            throw new IllegalStateException("Installment " + seq + " overpaid");
        }
        if (outstanding() == 0) {
            status = Status.PAID;
        }
    }

    long outstanding() {
        return (penaltyDue - penaltyPaid) + (feeDue - feePaid) + (interestDue - interestPaid)
                + (principalDue - principalPaid);
    }

    RepaymentAllocator.Outstanding toOutstanding() {
        return new RepaymentAllocator.Outstanding(seq, penaltyDue - penaltyPaid, feeDue - feePaid,
                interestDue - interestPaid, principalDue - principalPaid);
    }

    int seq() { return seq; }
}
