package com.example.hybridrag.infrastructure.llm;

import com.example.hybridrag.domain.dto.ExamDraftRequest;
import com.example.hybridrag.domain.dto.ExamDraftResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // =============================
    // Prompt templates (giữ nguyên như bạn đưa)
    // =============================
    private static final String SYSTEM_TEMPLATE = """
            Bạn là chuyên gia thiết kế đề thi tiếng Anh (TOEIC/IELTS).
            Bạn PHẢI chỉ xuất ra MỘT JSON object hợp lệ theo schema được cung cấp.
            Không markdown. Không thêm chữ ngoài JSON. Không giải thích ngoài schema.
            """;

    private static final String USER_TEMPLATE = """
            SYSTEM REQUIREMENTS
            - Output: ONLY one JSON object matching the schema below.
            - Do not include any extra keys.
            - source_page_number must always be null.
            
            TASK
            Create exactly {total_questions} multiple-choice questions about the topic below, using SOURCE TEXT when required.
            
            TOPIC
            {topic}
            
            SOURCE TEXT
            {context}
            
            CONFIG
            - total_questions: {total_questions}
            - question_type_distribution: {question_type_distribution_human}
            - difficulty_distribution: {difficulty_distribution_human}
            - explanation_language: {explanation_language_human}
            
            HARD RULES
            1) Each question has 4 options: option_a/option_b/option_c/option_d.
            2) correct_answer is one of: "A","B","C","D".
            3) Balanced correct answers across A/B/C/D; do not repeat the same letter more than 2 times consecutively; avoid obvious patterns.
            4) explanation: explain ONLY why the correct answer is correct. Do NOT mention other options.
            5) explanation_wrong_a/b/c/d: explain why that option is wrong. Must not duplicate the main explanation. Must not use the word "đúng" for wrong options.
            6) If an option is the correct answer, its explanation_wrong_* must be EXACTLY (character-by-character, same accents, same spacing):
               "Phương án này KHÔNG sai vì đây là đáp án đúng."
            7) No contradictions between explanations.
            
            CONTENT CONSTRAINTS
            - source_excerpt depends on question_type:
              * reading: use the SAME English passage for all reading questions. The passage must be created based on SOURCE TEXT. source_excerpt = a verbatim excerpt from that passage.
              * grammar: "TOPIC-BASED (NO SOURCE TEXT DEPENDENCY)"
              * vocabulary: "TOPIC-BASED (NO SOURCE TEXT DEPENDENCY)"
              * listening: create a fictional transcript relevant to the topic (do NOT copy SOURCE TEXT). source_excerpt = a verbatim excerpt from that transcript.
            - grammar/vocabulary/listening must not depend on SOURCE TEXT.
            
            DIFFICULTY RULES (COUNT ENGLISH WORDS in question_text)
            - easy: explicit info, <=12 words question_text, at least 2 plausible distractors.
            - medium: 1-step inference, 15-25 words question_text, at least 2 plausible distractors.
            - hard: multi-step inference, >=25 words question_text, at least 3 plausible distractors.
            
            CROSS-CHECK RULES (MUST PASS BEFORE OUTPUT)
            A) If question_type="listening":
               - Create ONE fictional transcript (English) internally for that question.
               - source_excerpt MUST be a verbatim excerpt from that transcript.
               - Transcript MUST NOT copy or paraphrase SOURCE TEXT.
            B) If a question uses SOURCE TEXT facts, it MUST be question_type="reading".
            C) Rule 6 must match EXACTLY; do NOT output decomposed unicode.
            D) Ensure difficulty word-count constraints are satisfied.
            E) If any rule fails, fix internally and output only the final JSON.
            
            OUTPUT SCHEMA
            {output_format}
            """;

    // ===== only helper we keep =====
    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String render(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static String toHumanDistribution(Map<String, Integer> dist) {
        if (dist == null || dist.isEmpty()) return "";
        return dist.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private static String buildOutputFormat(int totalQuestions) {
        return """
                {
                  "type": "object",
                  "required": ["questions"],
                  "additionalProperties": false,
                  "properties": {
                    "questions": {
                      "type": "array",
                      "minItems": %d,
                      "maxItems": %d,
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": [
                          "question_text",
                          "question_type",
                          "difficulty",
                          "option_a",
                          "option_b",
                          "option_c",
                          "option_d",
                          "correct_answer",
                          "explanation",
                          "explanation_wrong_a",
                          "explanation_wrong_b",
                          "explanation_wrong_c",
                          "explanation_wrong_d",
                          "source_excerpt",
                          "source_page_number"
                        ],
                        "properties": {
                          "question_text": { "type": "string", "minLength": 5 },
                          "question_type": { "type": "string", "enum": ["reading", "grammar", "vocabulary", "listening"] },
                          "difficulty": { "type": "string", "enum": ["easy", "medium", "hard"] },
                          "option_a": { "type": "string", "minLength": 1 },
                          "option_b": { "type": "string", "minLength": 1 },
                          "option_c": { "type": "string", "minLength": 1 },
                          "option_d": { "type": "string", "minLength": 1 },
                          "correct_answer": { "type": "string", "enum": ["A", "B", "C", "D"] },
                          "explanation": { "type": "string", "minLength": 15 },
                          "explanation_wrong_a": { "type": "string", "minLength": 5 },
                          "explanation_wrong_b": { "type": "string", "minLength": 5 },
                          "explanation_wrong_c": { "type": "string", "minLength": 5 },
                          "explanation_wrong_d": { "type": "string", "minLength": 5 },
                          "source_excerpt": { "type": "string", "minLength": 10 },
                          "source_page_number": { "type": ["integer", "null"] }
                        }
                      }
                    }
                  }
                }
                """.formatted(totalQuestions, totalQuestions);
    }

    private PromptBundle buildPrompt(ExamDraftRequest req, String context) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("topic", safe(req.getTopic()));
        vars.put("total_questions", String.valueOf(req.getTotalQuestions()));
        vars.put("question_type_distribution_human", toHumanDistribution(req.getTypeDistribution()));
        vars.put("difficulty_distribution_human", toHumanDistribution(req.getLevelDistribution()));
        vars.put("explanation_language_human", "vi");
        vars.put("output_format", buildOutputFormat(req.getTotalQuestions()));
        return new PromptBundle(SYSTEM_TEMPLATE, USER_TEMPLATE, vars, safe(context));
    }

    private record PromptBundle(String systemTemplate,
                                String userTemplate,
                                Map<String, String> vars,
                                String context) { }

    // =============================
    // ONLY check totalQuestions input
    // =============================
    private static void validateTotalOnly(ExamDraftRequest req) {
        if (req == null) throw new IllegalArgumentException("ExamDraftRequest is null");
        if (req.getTotalQuestions() <= 0) throw new IllegalArgumentException("totalQuestions must be > 0");
    }

    // =============================
    // Public API
    // =============================
    @Retryable(
            retryFor = {RuntimeException.class},
            maxAttemptsExpression = "#{${hybridrag.deepseek.retries:2} + 1}",
            backoff = @Backoff(delay = 200, multiplier = 2.0)
    )
    public ExamDraftResponse generateExamDraft(ExamDraftRequest request, String context) {
        if (apiKey.isBlank()) throw new IllegalStateException("DEEPSEEK_API_KEY is required for DeepSeek calls.");
        validateTotalOnly(request);

        PromptBundle pb = buildPrompt(request, context);
        String raw = chatWithPrompt(pb.systemTemplate(), pb.userTemplate(), pb.vars(), pb.context());

        try {
            return objectMapper.readValue(raw, ExamDraftResponse.class);
        } catch (Exception e) {
            log.warn("event=deepseek_parse_failed body_snip={} err={}", snippet(raw), e.toString());
            throw new RuntimeException("Failed to parse DeepSeek JSON", e);
        }
    }

    // =============================
    // Core call: chatWithPrompt
    // =============================
    private String chatWithPrompt(String systemTemplate,
                                  String userTemplate,
                                  Map<String, String> vars,
                                  String context) {

        Map<String, String> all = new LinkedHashMap<>();
        if (vars != null) all.putAll(vars);
        all.put("context", context == null ? "" : context);

        String system = render(systemTemplate, all);
        String user = render(userTemplate, all);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("response_format", Map.of("type", "json_object"));
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

            Map<?, ?> parsed = objectMapper.readValue(resp.body(), Map.class);
            Object choices = parsed.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty()) {
                throw new RuntimeException("DeepSeek response missing choices");
            }
            Object first = list.getFirst();
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

    private static String snippet(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "...";
    }
}