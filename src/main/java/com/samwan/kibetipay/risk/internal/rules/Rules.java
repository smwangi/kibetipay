package com.samwan.kibetipay.risk.internal.rules;

import com.samwan.kibetipay.risk.RiskApi.RiskRequest;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Starter rule set. Points are additive and capped at 100 by the engine. */
final class Rules {

    private Rules() {
    }

    @Component
    @Order(1)
    static class LargeAmountRule implements RiskRule {
        @Override
        public Optional<Hit> evaluate(RiskRequest r, Context c) {
            return r.amount().minor() >= c.props().largeAmountMinor()
                    ? Optional.of(new Hit(35, "large_amount"))
                    : Optional.empty();
        }
    }

    @Component
    @Order(2)
    static class VelocityRule implements RiskRule {
        @Override
        public Optional<Hit> evaluate(RiskRequest r, Context c) {
            return c.transfersInVelocityWindow() >= c.props().velocityMaxTransfers()
                    ? Optional.of(new Hit(40, "high_velocity"))
                    : Optional.empty();
        }
    }

    /** Draining most of the wallet in one go is a classic account-takeover signal. */
    @Component
    @Order(3)
    static class BalanceDrainRule implements RiskRule {
        @Override
        public Optional<Hit> evaluate(RiskRequest r, Context c) {
            long balance = r.subjectBalance().minor();
            return balance > 0 && r.amount().minor() * 100 / balance >= 80
                    ? Optional.of(new Hit(25, "drains_wallet"))
                    : Optional.empty();
        }
    }

    @Component
    @Order(4)
    static class NewCustomerRule implements RiskRule {
        @Override
        public Optional<Hit> evaluate(RiskRequest r, Context c) {
            return c.lifetimeAssessments() == 0
                    ? Optional.of(new Hit(15, "first_transfer"))
                    : Optional.empty();
        }
    }

    @Component
    @Order(5)
    static class NightTimeRule implements RiskRule {
        private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

        @Override
        public Optional<Hit> evaluate(RiskRequest r, Context c) {
            int hour = r.at().atZone(NAIROBI).getHour();
            return hour < 5 ? Optional.of(new Hit(10, "night_time")) : Optional.empty();
        }
    }
}
