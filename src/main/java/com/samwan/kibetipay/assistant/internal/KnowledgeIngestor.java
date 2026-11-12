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
 */
@Component
class KnowledgeIngestor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestor.class);

    private final VectorStore vectorStore;
    private final AssistantProperties props;

    KnowledgeIngestor(VectorStore vectorStore, AssistantProperties props) {
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
        var splitter = new TokenTextSplitter();   // default ~800-token chunks
        int chunks = 0;
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md");
            for (Resource file : files) {
                String text = file.getContentAsString(StandardCharsets.UTF_8);
                String source = file.getFilename();
                var doc = new Document(text, Map.of("source", source, "sha256", sha256(text)));
                List<Document> parts = splitter.apply(List.of(doc));
                vectorStore.delete("source == '" + source + "'");
                vectorStore.add(parts);
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
