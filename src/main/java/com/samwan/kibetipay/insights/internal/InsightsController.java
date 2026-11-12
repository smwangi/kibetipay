package com.samwan.kibetipay.insights.internal;

import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.insights.InsightsApi;
import com.samwan.kibetipay.insights.InsightsApi.SpendingSummary;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/insights/me")
class InsightsController {

    private final InsightsApi insights;
    private final ChatClient chat;

    InsightsController(InsightsApi insights, ChatClient.Builder builder) {
        this.insights = insights;
        this.chat = builder.defaultSystem("""
                You are a friendly personal-finance coach. Given a monthly spending summary in KES,
                write 3-4 sentences: the biggest categories, one notable pattern and one practical tip.
                Use only the numbers provided. No investment advice.
                """).build();
    }

    @GetMapping
    SpendingSummary summary(@RequestParam(required = false) YearMonth month) {
        return insights.spending(CurrentUser.id(), resolve(month));
    }

    /** Numbers come from SQL; the LLM only narrates them, so it cannot hallucinate totals. */
    @GetMapping("/narrative")
    Narrative narrative(@RequestParam(required = false) YearMonth month) {
        var summary = insights.spending(CurrentUser.id(), resolve(month));
        if (summary.byCategory().isEmpty()) {
            return new Narrative(summary, "No spending recorded for " + summary.month() + " yet.");
        }
        String text = chat.prompt().user(summary.toString()).call().content();
        return new Narrative(summary, text);
    }

    private static YearMonth resolve(YearMonth month) {
        return month != null ? month : YearMonth.now(ZoneId.of("Africa/Nairobi"));
    }

    record Narrative(SpendingSummary summary, String narrative) {}
}
