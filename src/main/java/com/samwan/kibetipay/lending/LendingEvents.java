package com.samwan.kibetipay.lending;

import java.time.Instant;
import java.util.UUID;

/** Public lending events. A notifications module, CRB reporting or analytics can subscribe later. */
public final class LendingEvents {

    private LendingEvents() {
    }

    /** Internal kick-off of the async approval/disbursement saga. */
    public record LoanApplied(UUID loanId) {}

    public record LoanDisbursed(UUID loanId, String borrowerId, UUID agentId, long principalMinor,
                                String currency, Instant at) {}

    public record LoanDeclined(UUID loanId, String borrowerId, String reasons, Instant at) {}

    public record RepaymentReceived(UUID loanId, UUID repaymentId, String borrowerId, String channel,
                                    long amountMinor, Instant at) {}

    public record LoanRepaid(UUID loanId, String borrowerId, UUID agentId, Instant at) {}

    public record LoanDefaulted(UUID loanId, String borrowerId, UUID agentId, long outstandingMinor, Instant at) {}
}
