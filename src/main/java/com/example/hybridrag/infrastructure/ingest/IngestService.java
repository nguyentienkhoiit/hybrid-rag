package com.example.hybridrag.infrastructure.ingest;

import com.example.hybridrag.domain.dto.ExamDraftRequest;
import com.example.hybridrag.infrastructure.search.ElasticsearchService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final PdfExtractor pdfExtractor;
    private final TokenTextChunker chunker;
    private final PgVectorStore vectorStore;
    private final ElasticsearchService elasticsearchService;

    public IngestService(
            PdfExtractor pdfExtractor,
            TokenTextChunker chunker,
            PgVectorStore vectorStore,
            ElasticsearchService elasticsearchService
    ) {
        this.pdfExtractor = pdfExtractor;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.elasticsearchService = elasticsearchService;
    }

    public record IngestResult(String fileId, int chunks) {}

    /**
     * Offline ingest pipeline (performed synchronously per request here):
     * PDF -> token chunking -> embeddings (Ollama) -> pgvector (PgVectorStore) -> raw text -> Elasticsearch bulk (BM25)
     */
    public IngestResult ingest(ExamDraftRequest request, MultipartFile pdf) {
        String fileId = UUID.randomUUID().toString();

        String extracted;
        try (InputStream is = pdf.getInputStream()) {
            extracted = pdfExtractor.extractText(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PDF upload", e);
        }

        List<String> chunks = chunker.chunk(extracted);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Uploaded PDF has no extractable text after chunking.");
        }

        List<Document> docsForVector = new ArrayList<>(chunks.size());
        List<Map<String, Object>> docsForEs = new ArrayList<>(chunks.size());

        Instant now = Instant.now();
        for (int i = 0; i < chunks.size(); i++) {
            UUID id = UUID.randomUUID();
            String content = chunks.get(i);

            Map<String, Object> md = new HashMap<>();
            md.put("fileId", fileId);
            md.put("topic", request.getTopic());
            md.put("chunkIndex", i);
            md.put("source", pdf.getOriginalFilename() == null ? "upload.pdf" : pdf.getOriginalFilename());
            md.put("createdAt", now.toString());

            Document doc = new Document(id.toString(), content, md);
            docsForVector.add(doc);

            docsForEs.add(ElasticsearchService.esDoc(id, fileId, content, md));
        }

        long t0 = System.nanoTime();
        vectorStore.add(docsForVector);
        long t1 = System.nanoTime();
        elasticsearchService.bulkIndex(docsForEs);
        long t2 = System.nanoTime();

        log.info("event=ingest_complete fileId={} chunks={} pg_ms={} es_ms={}",
                fileId,
                chunks.size(),
                (t1 - t0) / 1_000_000,
                (t2 - t1) / 1_000_000
        );

        return new IngestResult(fileId, chunks.size());
    }
}