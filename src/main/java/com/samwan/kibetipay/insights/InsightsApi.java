package com.samwan.kibetipay.insights;

import java.time.YearMonth;
import java.util.List;

public interface InsightsApi {

    SpendingSummary spending(String ownerId, YearMonth month);

    enum Category { FOOD, TRANSPORT, RENT, UTILITIES, SHOPPING, ENTERTAINMENT, FAMILY_SUPPORT, SAVINGS, BUSINESS, OTHER, UNCATEGORISED }

    record CategoryTotal(Category category, String amount, int count) {}

    record SpendingSummary(String ownerId, YearMonth month, String currency, String totalOut, String totalIn,
                           List<CategoryTotal> byCategory) {}
}
