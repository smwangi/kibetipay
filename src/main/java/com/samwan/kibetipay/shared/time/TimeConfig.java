package com.samwan.kibetipay.shared.time;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One injectable clock in the business time zone. Code that asks "what is today?" (due dates,
 * overdue checks, daily jobs) uses this, so tests can pin time with Clock.fixed(...).
 */
@Configuration(proxyBeanMethods = false)
class TimeConfig {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Nairobi");

    @Bean
    Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
