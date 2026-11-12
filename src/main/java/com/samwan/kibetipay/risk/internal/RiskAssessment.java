package com.samwan.kibetipay.risk.internal;

import com.samwan.kibetipay.risk.RiskApi.RiskDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Audit record of every decision: regulators and analysts need to know why money moved or didn't. */
@Entity
@Table(name = "risk_assessment")
class RiskAssessment {

    @Id
    private UUID id;
    @Column(name = "transfer_id", nullable = false, unique = true)
    private UUID transactionId;
    @Column(name = "subject_id", nullable = false)
    private String subjectId;
    @Column(nullable = false)
    private int score;
    @Column(nullable = false)
    private String decision;
    @Column(nullable = false)
    private String reasons;
    private String explanation;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RiskAssessment() {
    }

    RiskAssessment(RiskDecision d, String subjectId) {
        this.id = UUID.randomUUID();
        this.transactionId = d.transactionId();
        this.subjectId = subjectId;
        this.score = d.score();
        this.decision = d.outcome().name();
        this.reasons = String.join("|", d.reasons());
        this.explanation = d.explanation();
        this.createdAt = Instant.now();
    }

    RiskDecision toDecision() {
        List<String> r = reasons.isBlank() ? List.of() : Arrays.asList(reasons.split("\\|"));
        return new RiskDecision(transactionId, score, RiskDecision.Outcome.valueOf(decision), r, explanation);
    }
}
