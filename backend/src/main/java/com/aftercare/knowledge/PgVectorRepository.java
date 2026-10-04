package com.aftercare.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PgVectorRepository {
    private final JdbcTemplate jdbc;
    private final double minimumSimilarity;

    public PgVectorRepository(JdbcTemplate jdbc,
            @Value("${app.knowledge.minimum-similarity:0.65}") double minimumSimilarity) {
        this.jdbc = jdbc;
        this.minimumSimilarity = minimumSimilarity;
    }

    public List<Match> search(UUID tenantId, String categoryCode, Instant referenceTime,
            String embeddingModel, float[] queryVector) {
        String vector = vectorLiteral(queryVector);
        return jdbc.query("""
                SELECT kc.id AS chunk_id, kd.title, kv.version_no, kc.page_no, kc.content,
                    1 - (kc.embedding <=> CAST(? AS vector)) AS similarity
                FROM knowledge_chunks kc
                JOIN knowledge_versions kv ON kv.tenant_id = kc.tenant_id AND kv.id = kc.version_id
                JOIN knowledge_documents kd ON kd.tenant_id = kv.tenant_id AND kd.id = kv.document_id
                WHERE kc.tenant_id = ?
                  AND kd.status = 'ACTIVE'
                  AND kv.status = 'PUBLISHED'
                  AND kc.embedding_model = ?
                  AND kv.effective_from <= ?
                  AND (kv.effective_to IS NULL OR kv.effective_to > ?)
                  AND (CAST(? AS varchar) IS NULL OR kd.category_code = CAST(? AS varchar))
                  AND kc.embedding IS NOT NULL
                ORDER BY kc.embedding <=> CAST(? AS vector)
                LIMIT 20
                """, (rs, rowNum) -> new Match(rs.getObject("chunk_id", UUID.class),
                rs.getString("title"), rs.getInt("version_no"),
                (Integer) rs.getObject("page_no"), rs.getString("content"), rs.getDouble("similarity")),
                vector, tenantId, embeddingModel, Timestamp.from(referenceTime), Timestamp.from(referenceTime),
                categoryCode, categoryCode, vector).stream()
                .filter(match -> match.similarity() >= minimumSimilarity)
                .limit(8)
                .toList();
    }

    private static String vectorLiteral(float[] vector) {
        var literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(Float.toString(vector[index]));
        }
        return literal.append(']').toString();
    }

    public record Match(UUID chunkId, String documentTitle, int versionNo, Integer pageNo,
            String content, double similarity) {
    }
}
