package com.samwan.kibetipay.risk.internal;

import com.samwan.kibetipay.risk.RiskApi.RiskRequest;
import java.util.List;
import java.util.Optional;

/** Advisory, human-readable note for a risk decision. Never influences the decision itself. */
interface RiskExplainer {

    Optional<String> explain(RiskRequest request, int score, List<String> reasons);
}
