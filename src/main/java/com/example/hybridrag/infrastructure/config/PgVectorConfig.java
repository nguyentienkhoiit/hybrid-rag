package com.example.hybridrag.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class PgVectorConfig {

    private static final Logger log = LoggerFactory.getLogger(PgVectorConfig.class);

    @Bean
    public PgVectorStore pgVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            @Value("${hybridrag.pgvector.schema}") String schema,
            @Value("${hybridrag.pgvector.table}") String table,
            @Value("${hybridrag.pgvector.dimensions}") int dimensions,
            @Value("${hybridrag.pgvector.initialize-schema}") boolean initializeSchema,
            @Value("${hybridrag.pgvector.max-batch-size}") int maxBatchSize
    ) {
        log.info("event=pgvector_config schema={} table={} dimensions={} initSchema={} maxBatch={}",
                schema, table, dimensions, initializeSchema, maxBatchSize);

        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName(schema)
                .vectorTableName(table)
                .dimensions(dimensions)
                .initializeSchema(initializeSchema)
                .maxDocumentBatchSize(maxBatchSize)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .build();
    }
}