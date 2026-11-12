package com.samwan.kibetipay.search.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.BuiltinScriptLanguage;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Writes projections with <b>scripted upserts</b>: one round trip that creates the document if it is
 * missing and otherwise runs a Painless script against the stored {@code _source}.
 *
 * <p>Events are handled asynchronously, so they can arrive out of order or twice (outbox redelivery).
 * The scripts make every write order-independent and idempotent:
 * <ul>
 *   <li>facts carried by an event are simply (re)assigned, which is harmless when repeated;</li>
 *   <li>{@code status} only moves forward, by rank (HELD_FOR_REVIEW &lt; COMPLETED), so a late "held" event
 *       cannot overwrite "completed";</li>
 *   <li>{@code updatedAt} only grows.</li>
 * </ul>
 * Inline scripts with identical source are compiled once and cached by the cluster; only {@code params} vary.
 */
@Component
@ConditionalOnBooleanProperty("app.search.enabled")
class DocumentWriter {

    static final String STATUS_SCRIPT = """
            for (entry in params.fields.entrySet()) {
              ctx._source[entry.getKey()] = entry.getValue();
            }
            def current = ctx._source.status;
            if (current == null || params.ranks[params.status] >= params.ranks.getOrDefault(current, 0)) {
              ctx._source.status = params.status;
            }
            if (ctx._source.updatedAt == null || params.at > ctx._source.updatedAt) {
              ctx._source.updatedAt = params.at;
            }
            """;

    private final OpenSearchClient client;
    private final SearchIndices indices;

    DocumentWriter(OpenSearchClient client, SearchIndices indices) {
        this.client = client;
        this.indices = indices;
    }

    /** Assign {@code fields}, advance {@code status} if it ranks at least as high as the stored one. */
    void upsertWithStatus(SearchIndices.Kind kind, String id, Map<String, Object> fields, String status,
                          Map<String, Integer> ranks, Instant at) {
        scriptedUpsert(kind, id, STATUS_SCRIPT, Map.of(
                "fields", JsonData.of(fields),
                "status", JsonData.of(status),
                "ranks", JsonData.of(ranks),
                "at", JsonData.of(at.toEpochMilli())));
    }

    void scriptedUpsert(SearchIndices.Kind kind, String id, String script, Map<String, JsonData> params) {
        try {
            update(kind, id, script, params);
        } catch (OpenSearchException e) {
            if (!isMissingAlias(e)) {
                throw e;
            }
            indices.ensure(kind);   // cluster was down at startup: create lazily, then retry once
            update(kind, id, script, params);
        }
    }

    private void update(SearchIndices.Kind kind, String id, String script, Map<String, JsonData> params) {
        try {
            client.update(u -> u
                            .index(indices.alias(kind))
                            .id(id)
                            .requireAlias(true)
                            .retryOnConflict(5)          // concurrent events for the same document
                            .scriptedUpsert(true)
                            .upsert(Map.of())
                            .script(s -> s.inline(i -> i
                                    .lang(l -> l.builtin(BuiltinScriptLanguage.Painless))
                                    .source(script)
                                    .params(params))),
                    Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isMissingAlias(OpenSearchException e) {
        return "index_not_found_exception".equals(e.error().type());
    }

    /** Builds a field map, skipping nulls so a partial event never blanks out a field another event set. */
    static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
