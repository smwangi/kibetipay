package com.samwan.kibetipay.search.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.samwan.kibetipay.search.internal.SearchIndices.Kind;
import com.samwan.kibetipay.search.internal.SearchPage.Hit;
import com.samwan.kibetipay.search.internal.SearchPage.Paging;
import com.samwan.kibetipay.shared.money.Money;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.aggregations.CalendarInterval;
import org.opensearch.client.opensearch._types.aggregations.StatsAggregate;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Full-text + filtered search over transfers, with facets.
 *
 * <p>Query anatomy (the core OpenSearch mental model):
 * <pre>
 * bool
 * ├── must     scored: free text across note (stemmed, fuzzy), note.prefix (as-you-type) and party ids
 * └── filter   unscored, cacheable yes/no: status, party, amount range, date range, viewer scope
 * aggs         status counts, transfers per day, amount stats, over ALL matches, not just the page
 * highlight    note fragments with &lt;em&gt; around matched terms
 * </pre>
 */
@Service
@ConditionalOnBooleanProperty("app.search.enabled")
class TransferSearch {

    static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransferDoc(String transferId, String initiatorId, String recipientId, Long amountMinor, String currency,
                       String note, Integer riskScore, String status, String reason, Long updatedAt) {}

    record Criteria(String text, String status, String party, BigDecimal minAmount, BigDecimal maxAmount,
                    LocalDate from, LocalDate to, Paging paging) {}

    record TransferView(String transferId, String initiatorId, String recipientId, String amount, String note,
                        Integer riskScore, String status, String reason, Instant updatedAt) {}

    record Facets(Map<String, Long> byStatus, List<DayCount> perDay, AmountStats amount) {}

    record DayCount(String day, long count) {}

    record AmountStats(long count, String min, String max, String avg, String sum) {}

    private final OpenSearchClient client;
    private final SearchIndices indices;
    private final String currency;

    TransferSearch(OpenSearchClient client, SearchIndices indices,
                   @Value("${app.ledger.default-currency}") String currency) {
        this.client = client;
        this.indices = indices;
        this.currency = currency;
    }

    /** @param viewer null = admin (everything); otherwise only transfers the viewer sent or received */
    SearchPage<TransferView, Facets> search(Criteria c, String viewer) {
        boolean hasText = StringUtils.hasText(c.text());
        Query text = hasText ? textQuery(c.text().trim()) : Query.of(q -> q.matchAll(m -> m));
        List<Query> filters = filters(c, viewer);

        try {
            SearchResponse<TransferDoc> response = client.search(s -> s
                            .index(indices.alias(Kind.TRANSFERS))
                            .query(q -> q.bool(b -> b.must(text).filter(filters)))
                            .from(c.paging().from())
                            .size(c.paging().size())
                            .trackTotalHits(t -> t.enabled(true))
                            .sort(so -> hasText ? so.score(sc -> sc.order(SortOrder.Desc))
                                                : so.field(f -> f.field("updatedAt").order(SortOrder.Desc)))
                            .sort(so -> so.field(f -> f.field("updatedAt").order(SortOrder.Desc)))
                            .highlight(h -> h
                                    .preTags("<em>").postTags("</em>")
                                    .fields("note", f -> f)
                                    .fields("note.prefix", f -> f))
                            .aggregations("by_status", a -> a.terms(t -> t.field("status").size(10)))
                            .aggregations("per_day", a -> a.dateHistogram(d -> d
                                    .field("updatedAt")
                                    .calendarInterval(CalendarInterval.Day)
                                    .timeZone(ZONE.getId())
                                    .format("yyyy-MM-dd")))
                            .aggregations("amount", a -> a.stats(st -> st.field("amountMinor"))),
                    TransferDoc.class);
            return toPage(response, c.paging());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code should} clauses with minimum_should_match=1: any one may match, and each match adds to the score.
     * The stemmed {@code note} field is boosted so whole-word hits outrank prefix-only hits.
     */
    private static Query textQuery(String text) {
        return Query.of(q -> q.bool(b -> b
                .should(s -> s.match(m -> m.field("note").query(FieldValue.of(text)).fuzziness("AUTO").boost(3f)))
                .should(s -> s.match(m -> m.field("note.prefix").query(FieldValue.of(text))))
                .should(s -> s.term(t -> t.field("initiatorId").value(FieldValue.of(text))))
                .should(s -> s.term(t -> t.field("recipientId").value(FieldValue.of(text))))
                .should(s -> s.term(t -> t.field("transferId").value(FieldValue.of(text))))
                .should(s -> s.match(m -> m.field("reason").query(FieldValue.of(text))))
                .minimumShouldMatch("1")));
    }

    private List<Query> filters(Criteria c, String viewer) {
        List<Query> filters = new ArrayList<>();
        if (viewer != null) {
            filters.add(eitherParty(viewer));
        }
        if (StringUtils.hasText(c.party())) {
            filters.add(eitherParty(c.party()));
        }
        if (StringUtils.hasText(c.status())) {
            filters.add(Query.of(q -> q.term(t -> t.field("status").value(FieldValue.of(c.status().toUpperCase())))));
        }
        if (c.minAmount() != null || c.maxAmount() != null) {
            filters.add(Query.of(q -> q.range(r -> {
                r.field("amountMinor");
                if (c.minAmount() != null) {
                    r.gte(JsonData.of(Money.ofMajor(c.minAmount(), currency).minor()));
                }
                if (c.maxAmount() != null) {
                    r.lte(JsonData.of(Money.ofMajor(c.maxAmount(), currency).minor()));
                }
                return r;
            })));
        }
        if (c.from() != null || c.to() != null) {
            filters.add(Query.of(q -> q.range(r -> {
                r.field("updatedAt");
                if (c.from() != null) {
                    r.gte(JsonData.of(c.from().atStartOfDay(ZONE).toInstant().toEpochMilli()));
                }
                if (c.to() != null) {
                    r.lt(JsonData.of(c.to().plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()));
                }
                return r;
            })));
        }
        return filters;
    }

    private static Query eitherParty(String userId) {
        return Query.of(q -> q.bool(b -> b
                .should(s -> s.term(t -> t.field("initiatorId").value(FieldValue.of(userId))))
                .should(s -> s.term(t -> t.field("recipientId").value(FieldValue.of(userId))))
                .minimumShouldMatch("1")));
    }

    private SearchPage<TransferView, Facets> toPage(SearchResponse<TransferDoc> response, Paging paging) {
        List<Hit<TransferView>> hits = response.hits().hits().stream()
                .map(h -> new Hit<>(view(h.source()), h.score(), h.highlight()))
                .toList();

        var aggs = response.aggregations();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        aggs.get("by_status").sterms().buckets().array().forEach(b -> byStatus.put(b.key(), b.docCount()));
        List<DayCount> perDay = aggs.get("per_day").dateHistogram().buckets().array().stream()
                .map(b -> new DayCount(b.keyAsString(), b.docCount()))
                .toList();

        long total = response.hits().total() == null ? hits.size() : response.hits().total().value();
        return new SearchPage<>(total, paging.page(), paging.size(), hits,
                new Facets(byStatus, perDay, stats(aggs.get("amount").stats())));
    }

    private AmountStats stats(StatsAggregate s) {
        if (s.count() == 0) {
            return new AmountStats(0, null, null, null, null);
        }
        return new AmountStats(s.count(), money(s.min()), money(s.max()), money(s.avg()), money(s.sum()));
    }

    private String money(Double minor) {
        return minor == null ? null : Money.of(Math.round(minor), currency).toString();
    }

    private TransferView view(TransferDoc d) {
        String amount = d.amountMinor() == null ? null
                : Money.of(d.amountMinor(), d.currency() != null ? d.currency() : currency).toString();
        Instant at = d.updatedAt() == null ? null : Instant.ofEpochMilli(d.updatedAt());
        return new TransferView(d.transferId(), d.initiatorId(), d.recipientId(), amount, d.note(),
                d.riskScore(), d.status(), d.reason(), at);
    }
}
