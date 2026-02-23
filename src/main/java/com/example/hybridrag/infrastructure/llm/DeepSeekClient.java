package com.example.hybridrag.infrastructure.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public DeepSeekClient(
            ObjectMapper objectMapper,
            @Value("${hybridrag.deepseek.base-url}") String baseUrl,
            @Value("${hybridrag.deepseek.api-key}") String apiKey,
            @Value("${hybridrag.deepseek.model}") String model,
            @Value("${hybridrag.deepseek.temperature}") double temperature,
            @Value("${hybridrag.deepseek.max-tokens}") int maxTokens,
            @Value("${hybridrag.deepseek.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${hybridrag.deepseek.read-timeout-ms}") int readTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        log.info("event=deepseek_client_config baseUrl={} model={} temp={} maxTokens={} connectTimeoutMs={} readTimeoutMs={}",
                baseUrl, model, temperature, maxTokens, connectTimeoutMs, readTimeoutMs);
    }

    @Retryable(
            retryFor = {RuntimeException.class},
            maxAttemptsExpression = "#{${hybridrag.deepseek.retries:2} + 1}",
            backoff = @Backoff(delay = 200, multiplier = 2.0)
    )
    public String chatAnswer(String topic, String question, String context) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is required for DeepSeek calls.");
        }

        String system = """
                You are a precise assistant. Answer ONLY using the provided context from a PDF.
                If the answer is not in the context, say: "I cannot find this in the provided document."
                Keep the answer concise, structured, and grounded with short quotes when helpful.
                """;

        String user = "Topic: " + safe(topic) + "\n"
                + "Question: " + safe(question) + "\n\n"
                + "Context:\n" + safe(context);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize DeepSeek request", e);
        }

        URI uri = URI.create(baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long t0 = System.nanoTime();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;

            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("event=deepseek_http_error status={} ms={} body_snip={}",
                        resp.statusCode(), ms, snippet(resp.body()));
                throw new RuntimeException("DeepSeek HTTP error: " + resp.statusCode());
            }

            Map parsed = objectMapper.readValue(resp.body(), Map.class);
            Object choices = parsed.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty()) {
                throw new RuntimeException("DeepSeek response missing choices");
            }
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> m)) {
                throw new RuntimeException("DeepSeek response invalid choices format");
            }
            Object msg = m.get("message");
            if (!(msg instanceof Map<?, ?> mm)) {
                throw new RuntimeException("DeepSeek response missing message");
            }
            Object content = mm.get("content");
            String answer = content == null ? "" : String.valueOf(content).trim();

            log.info("event=deepseek_ok ms={} chars_out={}", ms, answer.length());
            return answer;
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek request failed", e);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "...";
    }
}