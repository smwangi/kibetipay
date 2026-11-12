package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.wallet.WalletApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The scorecard is the safe default credit model. When the ML model arrives (phase 5), it is
 * added as a second bean, for example:
 *
 * <pre>{@code
 * @Bean
 * @ConditionalOnProperty("app.lending.credit-model.onnx-path")
 * CreditModel mlCreditModel(CreditModelProperties props) {
 *     return new OnnxCreditModel(props.onnxPath());
 * }
 * }</pre>
 *
 * and it wins automatically wherever a single {@link CreditModel} is injected. The scorecard
 * bean stays in the context, so it can still run as a "challenger" in shadow mode: inject
 * {@code List<CreditModel>} (which includes fallback beans) and log both scores to compare.
 */
@Configuration(proxyBeanMethods = false)
class CreditModelConfig {

    @Bean
    @Fallback
    CreditModel scorecardCreditModel(JdbcClient jdbc, WalletApi wallets) {
        return new ScorecardCreditModel(jdbc, wallets);
    }
}
