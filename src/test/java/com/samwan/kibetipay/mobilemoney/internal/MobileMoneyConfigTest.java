package com.samwan.kibetipay.mobilemoney.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.mobilemoney.MobileMoneyGateway;
import com.samwan.kibetipay.mobilemoney.MobileMoneyGateway.CollectionRequest;
import com.samwan.kibetipay.mobilemoney.MobileMoneyGateway.Status;
import com.samwan.kibetipay.shared.money.Money;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Proves the @Fallback contract with a tiny context: no database, no web server, milliseconds. */
class MobileMoneyConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MobileMoneyConfig.class);

    @Test
    void simulatorIsInjectedWhenNoRealGatewayExists() {
        runner.run(ctx -> assertThat(ctx.getBean(MobileMoneyGateway.class).provider()).isEqualTo("simulator"));
    }

    @Test
    void realGatewayWinsWithoutAmbiguity() {
        runner.withBean("daraja", MobileMoneyGateway.class, FakeDaraja::new).run(ctx -> {
            assertThat(ctx.getBeansOfType(MobileMoneyGateway.class)).hasSize(2);           // both exist...
            assertThat(ctx.getBean(MobileMoneyGateway.class).provider()).isEqualTo("fake-daraja"); // ...one wins
        });
    }

    @Test
    void simulatorIsDeterministicAndRejectsTestNumbers() {
        var sim = new SimulatedMobileMoneyGateway();
        var kes = Money.of(100_00, "KES");
        var first = sim.requestCollection(new CollectionRequest("key-1", "254712345678", kes, "LOAN-1", "repay"));
        var retry = sim.requestCollection(new CollectionRequest("key-1", "254712345678", kes, "LOAN-1", "repay"));
        var rejected = sim.requestCollection(new CollectionRequest("key-2", "254712340000", kes, "LOAN-1", "repay"));

        assertThat(retry.providerRequestId()).isEqualTo(first.providerRequestId());
        assertThat(first.status()).isEqualTo(Status.ACCEPTED);
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
    }

    static class FakeDaraja implements MobileMoneyGateway {
        @Override
        public String provider() {
            return "fake-daraja";
        }

        @Override
        public Requested requestCollection(CollectionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Requested requestPayout(PayoutRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
