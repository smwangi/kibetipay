package com.samwan.kibetipay.lending.internal;

import java.util.List;

/**
 * Port for credit decisions. Today: the transparent scorecard (the {@code @Fallback} bean in
 * {@link CreditModelConfig}). Later: an ML model that takes over just by being registered.
 */
interface CreditModel {

    /** Stored with every decision so you can always answer "which model declined this borrower?". */
    String name();

    Score score(String borrowerId, Product product, long principalMinor);

    record Score(int value, List<String> reasons, boolean hardDecline) {}
}
