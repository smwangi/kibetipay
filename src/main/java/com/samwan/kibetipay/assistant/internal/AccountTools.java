package com.samwan.kibetipay.assistant.internal;

import com.samwan.kibetipay.insights.InsightsApi;
import com.samwan.kibetipay.lending.LendingQueries;
import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.wallet.WalletApi;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Tools the model may call. A new instance is created per request, bound to the authenticated
 * user, so the model can never ask for someone else's data: there is no "ownerId" parameter
 * for it to fill in. All tools are READ-ONLY; the assistant cannot move money.
 */
public class AccountTools {

    private final String ownerId;
    private final WalletApi wallets;
    private final TransferQueries transfers;
    private final InsightsApi insights;
    private final LendingQueries loans;

    AccountTools(String ownerId, WalletApi wallets, TransferQueries transfers, InsightsApi insights,
                 LendingQueries loans) {
        this.ownerId = ownerId;
        this.wallets = wallets;
        this.transfers = transfers;
        this.insights = insights;
        this.loans = loans;
    }

    @Tool(description = "List the current user's loans with status, outstanding balance, next due date and days past due")
    public List<LendingQueries.LoanSummary> myLoans() {
        return loans.loansOf(ownerId);
    }

    @Tool(description = "Get the current user's wallet balance")
    public String myBalance() {
        if (!wallets.exists(ownerId, wallets.defaultCurrency())) {
            return "The user has not opened a wallet yet.";
        }
        return wallets.get(ownerId, wallets.defaultCurrency()).balance().toString();
    }

    @Tool(description = "List the current user's most recent transfers (sent and received) with status and risk score")
    public List<TransferQueries.TransferView> myRecentTransfers(
            @ToolParam(description = "How many transfers to return, 1-20") int limit) {
        return transfers.recentFor(ownerId, Math.min(Math.max(limit, 1), 20));
    }

    @Tool(description = "Get the current user's spending by category for a month. Month format yyyy-MM; omit for current month")
    public InsightsApi.SpendingSummary mySpending(
            @ToolParam(description = "Month as yyyy-MM", required = false) String month) {
        var ym = month == null || month.isBlank() ? YearMonth.now(ZoneId.of("Africa/Nairobi")) : YearMonth.parse(month);
        return insights.spending(ownerId, ym);
    }
}
