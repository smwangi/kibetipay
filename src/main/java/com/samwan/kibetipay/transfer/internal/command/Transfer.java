package com.samwan.kibetipay.transfer.internal.command;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.samwan.kibetipay.shared.error.DomainException;
import com.samwan.kibetipay.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Transfer aggregate: an explicit state machine. Every transition is a method that checks
 * the current state, so illegal transitions (e.g. completing a rejected transfer) are impossible.
 *
 * <pre>
 *   PENDING_RISK ──approve──▶ COMPLETED
 *        │ ├──review───▶ IN_REVIEW ──approve──▶ COMPLETED
 *        │ │                   └────decline──▶ REJECTED
 *        │ └──reject───▶ REJECTED
 *        └──posting fails──▶ FAILED
 * </pre>
 */
@Entity
@Table(name = "transfer")
public class Transfer {

    public enum Status { PENDING_RISK, IN_REVIEW, COMPLETED, REJECTED, FAILED }

    private static final Set<Status> CAN_COMPLETE = EnumSet.of(Status.PENDING_RISK, Status.IN_REVIEW);

    @Id
    private UUID id;
    @Column(name = "initiator_id", nullable = false, updatable = false)
    private String initiatorId;
    @Column(name = "recipient_id", nullable = false, updatable = false)
    private String recipientId;
    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;
    @JdbcTypeCode(SqlTypes.CHAR)   // column is CHAR(3) in Flyway; Hibernate defaults to VARCHAR
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;
    @Column(length = 140, updatable = false)
    private String note;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;
    @Column(name = "risk_score")
    private Integer riskScore;
    @Column(name = "failure_reason")
    private String failureReason;
    @Column(name = "journal_id")
    private UUID journalId;
    @Version
    private long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Transfer() {
    }

    static Transfer initiate(String initiatorId, String recipientId, Money amount, String note,
                             String idempotencyKey) {
        var t = new Transfer();
        t.id = UUID.randomUUID();
        t.initiatorId = initiatorId;
        t.recipientId = recipientId;
        t.amountMinor = amount.minor();
        t.currency = amount.currencyCode();
        t.note = note;
        t.idempotencyKey = idempotencyKey;
        t.status = Status.PENDING_RISK;
        t.createdAt = t.updatedAt = Instant.now();
        return t;
    }

    void holdForReview(int score) {
        require(EnumSet.of(Status.PENDING_RISK));
        riskScore = score;
        moveTo(Status.IN_REVIEW);
    }

    void complete(UUID journalId, Integer score) {
        require(CAN_COMPLETE);
        if (score != null) {
            riskScore = score;
        }
        this.journalId = journalId;
        moveTo(Status.COMPLETED);
    }

    void reject(Integer score, String reason) {
        require(CAN_COMPLETE);
        if (score != null) {
            riskScore = score;
        }
        failureReason = reason;
        moveTo(Status.REJECTED);
    }

    void fail(String reason) {
        require(CAN_COMPLETE);
        failureReason = reason;
        moveTo(Status.FAILED);
    }

    boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.REJECTED || status == Status.FAILED;
    }

    private void require(Set<Status> allowed) {
        if (!allowed.contains(status)) {
            throw new DomainException.Conflict("invalid_transfer_state",
                    "Transfer " + id + " is " + status + ", expected one of " + allowed);
        }
    }

    private void moveTo(Status next) {
        status = next;
        updatedAt = Instant.now();
    }

    public UUID id() { return id; }
    public String initiatorId() { return initiatorId; }
    public String recipientId() { return recipientId; }
    public Money amount() { return Money.of(amountMinor, currency); }
    public String note() { return note; }
    public Status status() { return status; }
    public Integer riskScore() { return riskScore; }
    public Instant createdAt() { return createdAt; }
}
