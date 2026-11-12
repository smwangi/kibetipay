package com.samwan.kibetipay.mobilemoney.internal;

import com.samwan.kibetipay.mobilemoney.MobileMoneyGateway;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offline stand-in for M-Pesa, for dev, tests and demos without Daraja credentials.
 *
 * <ul>
 *   <li>Deterministic: the same idempotency key always yields the same provider id, which is how
 *       a real provider behaves on a retried request, so idempotency bugs show up locally.</li>
 *   <li>Test numbers: any MSISDN ending in 0000 is REJECTED (simulates "insufficient balance" /
 *       "request cancelled by user"), so failure paths can be exercised on purpose.</li>
 * </ul>
 */
class SimulatedMobileMoneyGateway implements MobileMoneyGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMobileMoneyGateway.class);

    @Override
    public String provider() {
        return "simulator";
    }

    @Override
    public Requested requestCollection(CollectionRequest r) {
        log.info("[simulator] STK push {} to {} for {} ref={}", r.idempotencyKey(), mask(r.msisdn()), r.amount(),
                r.accountReference());
        return respond("ws_CO_", r.idempotencyKey(), r.msisdn());
    }

    @Override
    public Requested requestPayout(PayoutRequest r) {
        log.info("[simulator] B2C payout {} to {} for {}", r.idempotencyKey(), mask(r.msisdn()), r.amount());
        return respond("AG_", r.idempotencyKey(), r.msisdn());
    }

    private Requested respond(String prefix, String key, String msisdn) {
        String id = prefix + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        if (msisdn.endsWith("0000")) {
            return new Requested(provider(), id, Status.REJECTED, "Simulated rejection for test number");
        }
        return new Requested(provider(), id, Status.ACCEPTED, "Accepted by simulator");
    }

    /** Phone numbers are personal data: never log them in full. */
    static String mask(String msisdn) {
        return msisdn.substring(0, 6) + "***" + msisdn.substring(msisdn.length() - 3);
    }
}
