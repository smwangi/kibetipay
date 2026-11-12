package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.mobilemoney.MobileMoneyGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

/**
 * The simulator is registered as a fallback. When the Daraja adapter is built (next integration
 * step) it is registered only if credentials are configured, for example:
 *
 * <pre>{@code
 * @Bean
 * @ConditionalOnProperty("app.mpesa.consumer-key")
 * MobileMoneyGateway darajaGateway(MpesaProperties props, RestClient.Builder http) {
 *     return new DarajaMobileMoneyGateway(props, http);
 * }
 * }</pre>
 *
 * With credentials: Daraja is injected. Without: the simulator is. No {@code if (prod)} anywhere,
 * and no profile name baked into the code.
 */
@Configuration(proxyBeanMethods = false)
class MobileMoneyConfig {

    @Bean
    @Fallback
    MobileMoneyGateway simulatedMobileMoneyGateway() {
        return new SimulatedMobileMoneyGateway();
    }
}
