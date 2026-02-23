package com.example.hybridrag.controller;

import com.example.hybridrag.application.service.RagApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagApplicationService ragApplicationService;

    public RagController(RagApplicationService ragApplicationService) {
        this.ragApplicationService = ragApplicationService;
    }

    @PostMapping(
            path = "/ask",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Map<String, Object>> ask(
            @RequestPart("topic") String topic,
            @RequestPart("file") MultipartFile file
    ) {
        if (!StringUtils.hasText(topic)) {
            throw new IllegalArgumentException("topic is required");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        if (file.getOriginalFilename() != null && !file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF uploads are supported");
        }

        RagApplicationService.AskResult result = ragApplicationService.ask(topic, file);
        return ResponseEntity.ok(Map.of(
                "fileId", result.fileId(),
                "answer", result.answer()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(HttpServletRequest req, Exception ex) {
        String path = req.getRequestURI();
        log.warn("event=api_error path={} msg={}", path, ex.getMessage(), ex);

        int status = 500;
        if (ex instanceof IllegalArgumentException) {
            status = 400;
        }

        return ResponseEntity.status(status).body(Map.of(
                "error", ex.getClass().getSimpleName(),
                "message", ex.getMessage(),
                "path", path
        ));
    }
}
