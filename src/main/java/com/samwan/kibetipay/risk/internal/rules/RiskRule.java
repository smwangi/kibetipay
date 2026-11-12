package com.samwan.kibetipay.risk.internal.rules;

import com.samwan.kibetipay.risk.RiskApi.RiskRequest;
import com.samwan.kibetipay.risk.internal.RiskProperties;
import java.util.Optional;

/**
 * Strategy pattern: every rule is a Spring bean, the engine collects them all.
 * Adding a rule = adding a class. Phase 5 adds an {@code MlModelRule} that calls a trained
 * model (e.g. gradient boosting exported to ONNX) and contributes its probability as points.
 */
public interface RiskRule {

    Optional<Hit> evaluate(RiskRequest request, Context context);

    /** Facts the engine pre-computes once so rules stay pure and fast. */
    record Context(long transfersInVelocityWindow, long lifetimeAssessments, RiskProperties props) {}

    record Hit(int points, String reason) {}
}
