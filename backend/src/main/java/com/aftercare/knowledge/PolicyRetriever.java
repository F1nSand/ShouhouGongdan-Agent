package com.aftercare.knowledge;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.aftercare.common.ApiException;
import com.aftercare.identity.ActorContext;
import com.aftercare.order.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PolicyRetriever {
    private final OrderRepository orderRepository;
    private final PgVectorRepository vectorRepository;
    private final EmbeddingProvider embeddingProvider;
    private final Clock clock;

    public PolicyRetriever(OrderRepository orderRepository, PgVectorRepository vectorRepository,
            EmbeddingProvider embeddingProvider, Clock clock) {
        this.orderRepository = orderRepository;
        this.vectorRepository = vectorRepository;
        this.embeddingProvider = embeddingProvider;
        this.clock = clock;
    }

    public PolicyEvidence retrieve(ActorContext actor, String query, UUID orderId, String categoryCode) {
        if (query == null || query.isBlank() || query.length() > 2000) {
            throw new ApiException("INVALID_ARGUMENT", "检索内容不能为空且不能超过 2000 个字符", HttpStatus.BAD_REQUEST);
        }
        Instant referenceTime = orderId == null ? clock.instant()
                : orderRepository.findById(actor.tenantId(), orderId)
                        .orElseThrow(() -> new ApiException("RESOURCE_NOT_FOUND", "未找到该订单", HttpStatus.NOT_FOUND))
                        .purchasedAt();
        float[] vector;
        try {
            vector = embeddingProvider.embedQuery(query);
        }
        catch (RuntimeException unavailable) {
            throw new ApiException("KNOWLEDGE_UNAVAILABLE", "当前无法检索政策知识", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (vector == null || vector.length != 1024) {
            throw new ApiException("KNOWLEDGE_UNAVAILABLE", "政策向量维度不匹配", HttpStatus.SERVICE_UNAVAILABLE);
        }
        var matches = vectorRepository.search(actor.tenantId(), categoryCode, referenceTime,
                embeddingProvider.modelName(), vector);
        return new PolicyEvidence(matches.stream().map(match -> new PolicyEvidence.Chunk(match.chunkId(),
                match.documentTitle(), match.versionNo(), match.pageNo(), match.content())).toList());
    }
}
