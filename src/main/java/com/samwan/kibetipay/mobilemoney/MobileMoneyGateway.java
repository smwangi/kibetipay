package com.samwan.kibetipay.mobilemoney;

import com.samwan.kibetipay.shared.money.Money;
import java.util.Objects;

/**
 * Port (hexagonal architecture) to a mobile-money provider. The rest of the system talks to this
 * interface only; M-Pesa Daraja, Airtel Money or a simulator are adapters behind it.
 *
 * <p>Both operations are ASYNCHRONOUS at the provider: "ACCEPTED" means the provider took the
 * request, not that money moved. The final result arrives later on a callback (webhook), and only
 * then may the ledger be posted. Confusing the two is the classic mobile-money integration bug.
 */
public interface MobileMoneyGateway {

    /** e.g. "simulator", "mpesa-daraja". Stored with every request for audit and reconciliation. */
    String provider();

    /** Collection: prompt the customer's phone to approve a payment (M-Pesa STK push). */
    Requested requestCollection(CollectionRequest request);

    /** Disbursement: send money to a phone (M-Pesa B2C), e.g. a loan payout. */
    Requested requestPayout(PayoutRequest request);

    /**
     * @param idempotencyKey our id for the request; resending the same key must not charge twice
     * @param msisdn         Kenyan mobile number in international format, e.g. 254712345678
     */
    record CollectionRequest(String idempotencyKey, String msisdn, Money amount, String accountReference,
                             String description) {
        public CollectionRequest {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Msisdn.require(msisdn);
            requirePositive(amount);
        }
    }

    record PayoutRequest(String idempotencyKey, String msisdn, Money amount, String remarks) {
        public PayoutRequest {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Msisdn.require(msisdn);
            requirePositive(amount);
        }
    }

    /** Provider's acknowledgement. {@code providerRequestId} is what the callback will reference. */
    record Requested(String provider, String providerRequestId, Status status, String message) {}

    enum Status { ACCEPTED, REJECTED }

    final class Msisdn {
        private Msisdn() {
        }

        /** Safaricom/Airtel style 2547XXXXXXXX or 2541XXXXXXXX. Normalise 07.../+254... at the edge. */
        static void require(String msisdn) {
            if (msisdn == null || !msisdn.matches("254[17]\\d{8}")) {
                throw new IllegalArgumentException("MSISDN must look like 254712345678");
            }
        }
    }

    private static void requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
