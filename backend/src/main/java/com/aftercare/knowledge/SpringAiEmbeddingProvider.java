package com.aftercare.knowledge;

import java.util.List;

import com.aftercare.config.EmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("live")
public class SpringAiEmbeddingProvider implements EmbeddingProvider {
    private final EmbeddingModel embeddingModel;
    private final int dimensions;
    private final String modelName;

    public SpringAiEmbeddingProvider(EmbeddingModel embeddingModel, EmbeddingProperties properties,
            @Value("${app.embedding.model:text-embedding-v3}") String modelName) {
        this.embeddingModel = embeddingModel;
        this.dimensions = properties.dimensions();
        this.modelName = modelName;
    }

    @Override
    public float[] embedQuery(String text) {
        return validate(embeddingModel.embed(text));
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return embeddingModel.embed(texts).stream().map(this::validate).toList();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private float[] validate(float[] vector) {
        if (vector == null || vector.length != dimensions) {
            throw new IllegalStateException("Embedding provider returned a vector with the wrong dimension");
        }
        return vector;
    }
}
