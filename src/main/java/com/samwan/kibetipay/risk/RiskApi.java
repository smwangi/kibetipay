package com.samwan.kibetipay.risk;

import com.samwan.kibetipay.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pre-authorisation risk scoring. Deliberately knows nothing about transfers: it scores a
 * generic money movement, so deposits, withdrawals or card payments can reuse it later.
 *
 * <p>Design rule: the decision is deterministic (rules + later an ML model). An LLM may
 * <em>explain</em> a decision for human reviewers but never <em>makes</em> it, and an LLM
 * outage never blocks a payment.
 */
public interface RiskApi {

    RiskDecision assess(RiskRequest request);

    record RiskRequest(UUID transactionId, String subjectId, String counterpartyId,
                       Money amount, Money subjectBalance, Instant at) {}

    record RiskDecision(UUID transactionId, int score, Outcome outcome, List<String> reasons,
                        String explanation) {

        public enum Outcome { APPROVE, REVIEW, REJECT }
    }
}
