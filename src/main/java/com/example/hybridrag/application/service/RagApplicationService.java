package com.example.hybridrag.application.service;

import com.example.hybridrag.infrastructure.ingest.IngestService;
import com.example.hybridrag.infrastructure.llm.DeepSeekClient;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RagApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RagApplicationService.class);

    private final IngestService ingestService;
    private final HybridSearchService hybridSearchService;
    private final DeepSeekClient deepSeekClient;

    private final int maxContextChars;
    private final int maxContextChunks;

    public RagApplicationService(
            IngestService ingestService,
            HybridSearchService hybridSearchService,
            DeepSeekClient deepSeekClient,
            @Value("${hybridrag.rag.context.max-chars}") int maxContextChars,
            @Value("${hybridrag.rag.context.max-chunks}") int maxContextChunks
    ) {
        this.ingestService = ingestService;
        this.hybridSearchService = hybridSearchService;
        this.deepSeekClient = deepSeekClient;
        this.maxContextChars = Math.max(500, maxContextChars);
        this.maxContextChunks = Math.max(1, maxContextChunks);
    }

    public record AskResult(String fileId, String answer) {}

    public AskResult ask(String topic, MultipartFile file) {
        long t0 = System.nanoTime();

        IngestService.IngestResult ingest = ingestService.ingest(topic, file);
        String fileId = ingest.fileId();

        // Use the topic as the query (per your API contract), but keep it flexible

        List<ScoredChunk> retrieved = hybridSearchService.hybridSearch(fileId, topic);

        String context = buildContext(retrieved, maxContextChunks, maxContextChars);

        String answer = deepSeekClient.chatAnswer(topic, topic, context);

        long t1 = System.nanoTime();
        log.info("event=rag_ask_done fileId={} chunks_ingested={} retrieved={} context_chars={} ms={}",
                fileId, ingest.chunks(), retrieved.size(), context.length(), (t1 - t0) / 1_000_000);

        return new AskResult(fileId, answer);
    }

    private static String buildContext(List<ScoredChunk> chunks, int maxChunks, int maxChars) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        int used = 0;

        for (ScoredChunk sc : chunks) {
            if (parts.size() >= maxChunks) {
                break;
            }
            String c = sc.content() == null ? "" : sc.content().trim();
            if (c.isEmpty()) {
                continue;
            }
            // Cheap per-chunk trimming to reduce tokens
            String trimmed = c.length() > 2000 ? c.substring(0, 2000) : c;

            String block = "[chunkId=" + sc.id() + " fused=" + String.format("%.4f", sc.fusedScore()) + "]\n" + trimmed;

            if (used + block.length() + 2 > maxChars) {
                int remaining = maxChars - used - 2;
                if (remaining > 200) {
                    parts.add(block.substring(0, Math.min(block.length(), remaining)));
                }
                break;
            }

            parts.add(block);
            used += block.length() + 2;
        }

        return String.join("\n\n", parts);
    }
}