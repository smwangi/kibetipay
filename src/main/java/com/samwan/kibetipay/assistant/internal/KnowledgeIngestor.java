package com.samwan.kibetipay.assistant.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * RAG ingestion: classpath:knowledge/*.md ─▶ chunks ─▶ embeddings ─▶ pgvector.
 *
 * <p>Re-runnable: each document is tagged with its source name and a content hash. On every run
 * the old chunks for that source are deleted and the new ones inserted, so editing a policy file
 * and restarting refreshes the index without duplicates. Phase 6 moves this into a scheduled job.
 * Without a configured vector store, ingestion is skipped and reports zero chunks.
 */
@Component
class KnowledgeIngestor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestor.class);

    private final ObjectProvider<VectorStore> vectorStore;
    private final AssistantProperties props;

    KnowledgeIngestor(ObjectProvider<VectorStore> vectorStore, AssistantProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ingestOnStartup() {
        if (props.ingestOnStartup()) {
            ingest();
        }
    }

    int ingest() {
        VectorStore store = vectorStore.getIfAvailable();
        if (store == null) {
            log.info("No vector store configured (spring.ai.vectorstore.type); knowledge ingestion skipped");
            return 0;
        }
        var splitter = new TokenTextSplitter();   // default ~800-token chunks
        int chunks = 0;
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md");
            for (Resource file : files) {
                String text = file.getContentAsString(StandardCharsets.UTF_8);
                String source = file.getFilename();
                var doc = new Document(text, Map.of("source", source, "sha256", sha256(text)));
                List<Document> parts = splitter.apply(List.of(doc));
                store.delete("source == '" + source + "'");
                store.add(parts);
                chunks += parts.size();
            }
            log.info("Ingested {} knowledge files into {} chunks", files.length, chunks);
        } catch (IOException | RuntimeException e) {
            // The assistant degrades (answers without policy context); payments are unaffected.
            log.warn("Knowledge ingestion failed: {}", e.getMessage());
        }
        return chunks;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
