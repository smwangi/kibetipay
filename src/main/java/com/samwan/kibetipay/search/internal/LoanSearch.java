package com.samwan.kibetipay.search.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.samwan.kibetipay.search.internal.SearchIndices.Kind;
import com.samwan.kibetipay.search.internal.SearchPage.Hit;
import com.samwan.kibetipay.search.internal.SearchPage.Paging;
import com.samwan.kibetipay.shared.money.Money;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Back-office loan search. The interesting part is the facet: a {@code terms} bucket per status with
 * {@code sum} sub-aggregations inside it, i.e. "GROUP BY status" with totals, computed by the cluster.
 */
@Service
@ConditionalOnBooleanProperty("app.search.enabled")
class LoanSearch {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LoanDoc(String loanId, String borrowerId, String agentId, Long principalMinor, String currency,
                   String status, String declineReasons, Long repaidMinor, Integer repaymentCount,
                   Long outstandingMinor, Long disbursedAt, Long lastRepaymentAt, Long updatedAt) {}

    record Criteria(String text, String status, String agentId, String borrower, Paging paging) {}

    record LoanView(String loanId, String borrowerId, String agentId, String principal, String repaid,
                    Integer repaymentCount, String outstandingAtDefault, String status, String declineReasons,
                    Instant disbursedAt, Instant lastRepaymentAt, Instant updatedAt) {}

    record StatusTotals(String status, long loans, String principal, String repaid) {}

    private final OpenSearchClient client;
    private final SearchIndices indices;
    private final String currency;

    LoanSearch(OpenSearchClient client, SearchIndices indices,
               @Value("${app.ledger.default-currency}") String currency) {
        this.client = client;
        this.indices = indices;
        this.currency = currency;
    }

    SearchPage<LoanView, List<StatusTotals>> search(Criteria c) {
        Query text = StringUtils.hasText(c.text())
                ? textQuery(c.text().trim())
                : Query.of(q -> q.matchAll(m -> m));
        List<Query> filters = new ArrayList<>();
        if (StringUtils.hasText(c.status())) {
            filters.add(term("status", c.status().toUpperCase()));
        }
        if (StringUtils.hasText(c.agentId())) {
            filters.add(term("agentId", c.agentId()));
        }
        if (StringUtils.hasText(c.borrower())) {
            filters.add(term("borrowerId", c.borrower()));
        }

        try {
            SearchResponse<LoanDoc> response = client.search(s -> s
                            .index(indices.alias(Kind.LOANS))
                            .query(q -> q.bool(b -> b.must(text).filter(filters)))
                            .from(c.paging().from())
                            .size(c.paging().size())
                            .trackTotalHits(t -> t.enabled(true))
                            .sort(so -> so.field(f -> f.field("updatedAt").order(SortOrder.Desc)))
                            .aggregations("by_status", a -> a
                                    .terms(t -> t.field("status").size(10))
                                    .aggregations("principal", sub -> sub.sum(sum -> sum.field("principalMinor")))
                                    .aggregations("repaid", sub -> sub.sum(sum -> sum.field("repaidMinor")))),
                    LoanDoc.class);

            List<Hit<LoanView>> hits = response.hits().hits().stream()
                    .map(h -> new Hit<>(view(h.source()), h.score(), h.highlight()))
                    .toList();
            List<StatusTotals> totals = response.aggregations().get("by_status").sterms().buckets().array().stream()
                    .map(b -> new StatusTotals(b.key(), b.docCount(),
                            money(b.aggregations().get("principal").sum().value()),
                            money(b.aggregations().get("repaid").sum().value())))
                    .toList();
            long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
            return new SearchPage<>(total, c.paging().page(), c.paging().size(), hits, totals);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Query textQuery(String text) {
        return Query.of(q -> q.bool(b -> b
                .should(s -> s.term(t -> t.field("loanId").value(FieldValue.of(text))))
                .should(s -> s.term(t -> t.field("borrowerId").value(FieldValue.of(text))))
                .should(s -> s.term(t -> t.field("agentId").value(FieldValue.of(text))))
                .should(s -> s.match(m -> m.field("declineReasons").query(FieldValue.of(text))))
                .minimumShouldMatch("1")));
    }

    private static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }

    private String money(Double minor) {
        return minor == null ? null : Money.of(Math.round(minor), currency).toString();
    }

    private String money(Long minor, String docCurrency) {
        return minor == null ? null : Money.of(minor, docCurrency != null ? docCurrency : currency).toString();
    }

    private static Instant instant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }

    private LoanView view(LoanDoc d) {
        return new LoanView(d.loanId(), d.borrowerId(), d.agentId(),
                money(d.principalMinor(), d.currency()), money(d.repaidMinor(), d.currency()), d.repaymentCount(),
                money(d.outstandingMinor(), d.currency()), d.status(), d.declineReasons(),
                instant(d.disbursedAt()), instant(d.lastRepaymentAt()), instant(d.updatedAt()));
    }
}
