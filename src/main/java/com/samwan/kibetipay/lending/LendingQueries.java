package com.samwan.kibetipay.lending;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of lending, used by REST and by the AI assistant's tools. */
public interface LendingQueries {

    List<LoanSummary> loansOf(String borrowerId);

    Optional<LoanDetail> loan(UUID loanId, String viewerId);

    record LoanSummary(UUID id, String product, String principal, String totalDue, String outstanding,
                       String status, Integer creditScore, int daysPastDue, LocalDate nextDueDate,
                       Instant appliedAt) {}

    record InstallmentView(int seq, LocalDate dueDate, String due, String paid, String penalty, String status) {}

    record LoanDetail(LoanSummary summary, String borrowerId, String agentUserId, String decisionReasons,
                      List<InstallmentView> schedule) {}
}
