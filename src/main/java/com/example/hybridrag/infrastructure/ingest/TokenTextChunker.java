package com.example.hybridrag.infrastructure.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenTextChunker {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final int chunkTokens;
    private final int overlapTokens;
    private final int minChars;

    public TokenTextChunker(
            @Value("${hybridrag.rag.chunk.tokens}") int chunkTokens,
            @Value("${hybridrag.rag.chunk.overlap}") int overlapTokens,
            @Value("${hybridrag.rag.chunk.min-chars}") int minChars
    ) {
        if (chunkTokens <= 0) {
            throw new IllegalArgumentException("chunkTokens must be > 0");
        }
        if (overlapTokens < 0 || overlapTokens >= chunkTokens) {
            throw new IllegalArgumentException("overlapTokens must be >= 0 and < chunkTokens");
        }
        this.chunkTokens = chunkTokens;
        this.overlapTokens = overlapTokens;
        this.minChars = minChars;
    }

    /**
     * Token-based chunking (approximation): tokens ~= whitespace-separated terms.
     * Overlap is applied as sliding window.
     */
    public List<String> chunk(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }

        String[] tokens = WHITESPACE.split(cleaned);
        List<String> chunks = new ArrayList<>();

        int start = 0;
        while (start < tokens.length) {
            int end = Math.min(tokens.length, start + chunkTokens);
            String chunk = join(tokens, start, end).trim();

            if (chunk.length() >= minChars) {
                chunks.add(chunk);
            } else {
                if (!chunks.isEmpty() && (chunks.get(chunks.size() - 1).length() < (minChars * 2))) {
                    String merged = (chunks.get(chunks.size() - 1) + "\n" + chunk).trim();
                    chunks.set(chunks.size() - 1, merged);
                } else if (!chunk.isBlank()) {
                    chunks.add(chunk);
                }
            }

            if (end >= tokens.length) {
                break;
            }
            start = Math.max(0, end - overlapTokens);
        }

        return chunks;
    }

    private static String join(String[] tokens, int start, int end) {
        StringBuilder sb = new StringBuilder(Math.max(0, (end - start) * 8));
        for (int i = start; i < end; i++) {
            sb.append(tokens[i]);
            if (i < end - 1) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}