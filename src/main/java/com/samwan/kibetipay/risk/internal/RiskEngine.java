package com.samwan.kibetipay.risk.internal;

import com.samwan.kibetipay.risk.RiskApi;
import com.samwan.kibetipay.risk.internal.rules.RiskRule;
import io.micrometer.observation.annotation.Observed;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

@Service
class RiskEngine implements RiskApi {

    private final List<RiskRule> rules;
    private final RiskAssessmentRepository assessments;
    private final RiskExplainer explainer;
    private final RiskProperties props;

    RiskEngine(List<RiskRule> rules, RiskAssessmentRepository assessments, RiskExplainer explainer,
               RiskProperties props) {
        this.rules = rules;
        this.assessments = assessments;
        this.explainer = explainer;
        this.props = props;
    }

    /**
     * Deliberately NOT @Transactional: the (slow) LLM call must not hold a DB connection.
     * Reads and the final insert are each short transactions of their own.
     */
    @Override
    @Observed(name = "risk.assess")
    public RiskDecision assess(RiskRequest request) {
        // Idempotent: a re-delivered event gets the original decision, not a second opinion.
        var existing = assessments.findByTransactionId(request.transactionId());
        if (existing.isPresent()) {
            return existing.get().toDecision();
        }

        var context = new RiskRule.Context(
                assessments.countBySubjectIdAndCreatedAtAfter(request.subjectId(),
                        request.at().minus(props.velocityWindow())),
                assessments.countBySubjectId(request.subjectId()),
                props);

        int score = 0;
        List<String> reasons = new ArrayList<>();
        for (RiskRule rule : rules) {
            var hit = rule.evaluate(request, context);
            if (hit.isPresent()) {
                score += hit.get().points();
                reasons.add(hit.get().reason());
            }
        }
        score = Math.min(score, 100);

        var outcome = score >= props.rejectAtOrAbove() ? RiskDecision.Outcome.REJECT
                : score >= props.approveBelow() ? RiskDecision.Outcome.REVIEW
                : RiskDecision.Outcome.APPROVE;

        // Advisory text for humans on non-approved decisions. Which explainer runs (LLM or no-op)
        // is decided once, at startup, by RiskExplainerConfig; no feature flag checks here.
        String explanation = outcome != RiskDecision.Outcome.APPROVE
                ? explainer.explain(request, score, reasons).orElse(null)
                : null;

        var decision = new RiskDecision(request.transactionId(), score, outcome, List.copyOf(reasons), explanation);
        try {
            assessments.saveAndFlush(new RiskAssessment(decision, request.subjectId()));
            return decision;
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            // Same transfer scored twice concurrently: the first stored decision wins.
            return assessments.findByTransactionId(request.transactionId()).orElseThrow().toDecision();
        }
    }
}
