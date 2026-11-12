package com.samwan.kibetipay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Modular monolith. Each direct sub-package is an application module:
 * shared (open kernel), wallet, transfer, risk, anomaly, insights, assistant.
 * Boundaries are verified by {@code ModularityTests}.
 */
@Modulithic(systemName = "KibetiPay", sharedModules = "shared")
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
@EnableResilientMethods   // Spring Framework 7 @Retryable / @ConcurrencyLimit
public class KibetiPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(KibetiPayApplication.class, args);
    }
}
