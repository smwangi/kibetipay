package com.samwan.kibetipay.lending;

/** Journal kinds emitted by lending workflows; persisted values are part of the ledger record. */
public final class LendingJournalKinds {
    public static final String CAPITAL = "CAPITAL";
    public static final String LOAN_DISBURSEMENT = "LOAN_DISBURSEMENT";
    public static final String LOAN_REPAYMENT = "LOAN_REPAYMENT";
    public static final String COMMISSION = "COMMISSION";

    private LendingJournalKinds() {}
}
