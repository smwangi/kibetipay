package com.samwan.kibetipay.lending.internal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.samwan.kibetipay.lending.internal.calc.RepaymentAllocator;
import com.samwan.kibetipay.lending.internal.calc.ScheduleCalculator;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Loan aggregate. Owns its installments; every state change goes through a method that
 * checks the current state.
 *
 * <pre>
 * APPLIED ─▶ ACTIVE ─▶ REPAID
 *   │  │        └────▶ DEFAULTED (days past due > threshold)
 *   │  └─▶ IN_REVIEW ─▶ ACTIVE | DECLINED
 *   └─▶ DECLINED
 * </pre>
 */
@Entity
@Table(name = "lending_loan")
class Loan {

    enum Status { APPLIED, IN_REVIEW, DECLINED, ACTIVE, REPAID, DEFAULTED }

    @Id
    private UUID id;
    @Column(name = "borrower_id", nullable = false, updatable = false)
    private String borrowerId;
    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;
    @Column(name = "agent_user_id", nullable = false, updatable = false)
    private String agentUserId;
    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;
    @Column(name = "principal_minor", nullable = false, updatable = false)
    private long principalMinor;
    @Column(name = "interest_minor", nullable = false)
    private long interestMinor;
    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;
    @JdbcTypeCode(SqlTypes.CHAR)   // column is CHAR(3) in Flyway; Hibernate defaults to VARCHAR
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "credit_score")
    private Integer creditScore;
    @Column(name = "decision_reasons")
    private String decisionReasons;
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;
    @Column(name = "disbursement_journal_id")
    private UUID disbursementJournalId;
    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;
    @Column(name = "disbursed_at")
    private Instant disbursedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Version
    private long version;

    @OneToMany(mappedBy = "loan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq")
    private List<Installment> installments = new ArrayList<>();

    protected Loan() {
    }

    static Loan apply(String borrowerId, UUID agentId, String agentUserId, Product product, Money principal,
                      String idempotencyKey) {
        var l = new Loan();
        l.id = UUID.randomUUID();
        l.borrowerId = borrowerId;
        l.agentId = agentId;
        l.agentUserId = agentUserId;
        l.productCode = product.code();
        l.principalMinor = principal.minor();
        l.currency = principal.currencyCode();
        var terms = product.terms(principal.minor());
        l.interestMinor = ScheduleCalculator.interestFor(terms);
        l.feeMinor = ScheduleCalculator.feeFor(terms);
        l.status = Status.APPLIED;
        l.idempotencyKey = idempotencyKey;
        l.appliedAt = Instant.now();
        return l;
    }

    void scored(int score, String reasons) {
        this.creditScore = score;
        this.decisionReasons = reasons;
    }

    void holdForReview() {
        require(EnumSet.of(Status.APPLIED));
        status = Status.IN_REVIEW;
    }

    void decline(String reasons) {
        require(EnumSet.of(Status.APPLIED, Status.IN_REVIEW));
        decisionReasons = reasons;
        status = Status.DECLINED;
        closedAt = Instant.now();
    }

    /** Builds the schedule at disbursement time, so due dates start from the real payout day. */
    void activate(UUID journalId, Product product, LocalDate today) {
        require(EnumSet.of(Status.APPLIED, Status.IN_REVIEW));
        disbursementJournalId = journalId;
        disbursedAt = Instant.now();
        status = Status.ACTIVE;
        installments.clear();
        for (var line : ScheduleCalculator.schedule(product.terms(principalMinor), today)) {
            installments.add(new Installment(this, line));
        }
    }

    /** Applies an allocation computed by {@link RepaymentAllocator}; closes the loan when fully paid. */
    boolean applyRepayment(RepaymentAllocator.Allocation allocation) {
        require(EnumSet.of(Status.ACTIVE, Status.DEFAULTED));   // recoveries after default are still welcome
        for (var part : allocation.parts()) {
            installments.stream().filter(i -> i.seq() == part.seq()).findFirst().orElseThrow().pay(part);
        }
        if (outstanding() == 0) {
            status = Status.REPAID;
            closedAt = Instant.now();
            return true;
        }
        return false;
    }

    void markDefaulted() {
        require(EnumSet.of(Status.ACTIVE));
        status = Status.DEFAULTED;
    }

    List<RepaymentAllocator.Outstanding> outstandingOldestFirst() {
        return installments.stream()
                .filter(i -> i.outstanding() > 0)
                .map(Installment::toOutstanding)
                .toList();
    }

    long outstanding() {
        return installments.stream().mapToLong(Installment::outstanding).sum();
    }

    private void require(Set<Status> allowed) {
        if (!allowed.contains(status)) {
            throw new DomainException.Conflict("invalid_loan_state", "Loan " + id + " is " + status);
        }
    }

    UUID id() { return id; }
    String borrowerId() { return borrowerId; }
    UUID agentId() { return agentId; }
    String agentUserId() { return agentUserId; }
    String productCode() { return productCode; }
    Money principal() { return Money.of(principalMinor, currency); }
    String currency() { return currency; }
    Status status() { return status; }
    String idempotencyKey() { return idempotencyKey; }
    Instant appliedAt() { return appliedAt; }
}
