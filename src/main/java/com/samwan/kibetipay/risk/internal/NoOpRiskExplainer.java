package com.samwan.kibetipay.risk.internal;

import com.samwan.kibetipay.risk.RiskApi.RiskRequest;
import java.util.List;
import java.util.Optional;

/** Used when no LLM is configured: decisions are stored without an analyst note. */
class NoOpRiskExplainer implements RiskExplainer {

    @Override
    public Optional<String> explain(RiskRequest request, int score, List<String> reasons) {
        return Optional.empty();
    }
}
