package com.samwan.kibetipay.search.internal;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.Requests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Index lifecycle: every index is versioned ({@code kibetipay-transfers-v1}) and the app only ever talks to
 * an alias ({@code kibetipay-transfers}).
 *
 * <p>Why: a mapping change (new analyzer, field type) needs a new index. With an alias you build
 * {@code -v2}, {@code _reindex} into it, then swap the alias atomically, with zero downtime and no code change.
 * Writes use {@code require_alias=true}, so a missing alias fails loudly instead of silently auto-creating
 * an index with guessed (dynamic) mappings.
 *
 * <p>Index bodies live in {@code src/main/resources/search/*.json} and are sent as raw JSON through the
 * generic client, so the files read exactly like the OpenSearch docs.
 */
@Component
@ConditionalOnBooleanProperty("app.search.enabled")
class SearchIndices {

    enum Kind {
        TRANSFERS("transfers", 1),
        LOANS("loans", 1);

        final String name;
        final int version;

        Kind(String name, int version) {
            this.name = name;
            this.version = version;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SearchIndices.class);

    private final OpenSearchClient client;
    private final String prefix;

    SearchIndices(OpenSearchClient client, SearchProperties props) {
        this.client = client;
        this.prefix = props.indexPrefix();
    }

    String alias(Kind kind) {
        return prefix + "-" + kind.name;
    }

    String concreteIndex(Kind kind) {
        return alias(kind) + "-v" + kind.version;
    }

    /** Best effort at startup: the app must boot even when the cluster is down (search is never critical). */
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        try {
            ensureAll();
        } catch (RuntimeException e) {
            log.warn("OpenSearch not reachable at startup ({}); indices will be created on first write", e.toString());
        }
    }

    void ensureAll() {
        for (Kind kind : Kind.values()) {
            ensure(kind);
        }
    }

    void ensure(Kind kind) {
        String alias = alias(kind);
        try {
            if (client.indices().existsAlias(a -> a.name(alias)).value()) {
                return;
            }
            var body = Json.createObjectBuilder(definition(kind))
                    .add("aliases", Json.createObjectBuilder()
                            .add(alias, Json.createObjectBuilder().add("is_write_index", true)));
            var request = Requests.builder().method("PUT").endpoint("/" + concreteIndex(kind)).json(body).build();
            try (var response = client.generic().execute(request)) {
                String text = response.getBody().map(b -> b.bodyAsString()).orElse("");
                if (response.getStatus() == 400 && text.contains("resource_already_exists_exception")) {
                    return;   // another instance won the race
                }
                if (response.getStatus() >= 300) {
                    throw new IllegalStateException("Creating " + concreteIndex(kind) + " failed: "
                            + response.getStatus() + " " + text);
                }
            }
            log.info("Created OpenSearch index {} behind alias {}", concreteIndex(kind), alias);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonObject definition(Kind kind) throws IOException {
        var resource = new ClassPathResource("search/" + kind.name + "-v" + kind.version + ".json");
        try (var in = resource.getInputStream(); var reader = Json.createReader(in)) {
            return reader.readObject();
        }
    }
}
