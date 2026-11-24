package com.samwan.kibetipay.shared.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

/**
 * Tags the current span with {@value #ATTRIBUTE} when money did not simply move: a payment failed,
 * was rejected, was held for a person, or has an unknown outcome.
 *
 * <p>The production Collector's tail sampler keeps every trace carrying this attribute (see
 * observability/collector/otel-collector.yaml), so those traces survive even though they are not
 * errors: a transfer refused for insufficient funds completes normally, with no exception.
 *
 * <p>Uses a high-cardinality key-value, which goes to the span only, never to metric tags.
 */
@Component
public class TraceOutcome {

    public static final String ATTRIBUTE = "kibetipay.outcome";

    private final ObservationRegistry observations;

    TraceOutcome(ObservationRegistry observations) {
        this.observations = observations;
    }

    /** {@code outcome} must come from a fixed vocabulary: failed, rejected, review, unknown, ... */
    public void mark(String outcome) {
        Observation current = observations.getCurrentObservation();
        if (current != null) {
            current.highCardinalityKeyValue(ATTRIBUTE, outcome);
        }
    }
}
