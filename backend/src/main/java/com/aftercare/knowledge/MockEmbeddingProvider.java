package com.aftercare.knowledge;

import java.util.List;
import java.util.Locale;

import com.aftercare.config.EmbeddingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
public class MockEmbeddingProvider implements EmbeddingProvider {
    private final int dimensions;
    private final String modelName;

    public MockEmbeddingProvider(EmbeddingProperties properties,
            @Value("${app.embedding.model:mock-char-ngram-v1}") String modelName) {
        this.dimensions = properties.dimensions();
        this.modelName = modelName;
    }

    @Override
    public float[] embedQuery(String text) {
        return vectorize(text);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return texts.stream().map(this::vectorize).toList();
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private float[] vectorize(String text) {
        int[] codePoints = text.toLowerCase(Locale.ROOT).codePoints().toArray();
        var vector = new float[dimensions];
        for (int index = 0; index < codePoints.length; index++) {
            addFeature(vector, codePoints[index]);
            if (index + 1 < codePoints.length) {
                addFeature(vector, 31 * codePoints[index] + codePoints[index + 1]);
            }
        }
        double lengthSquared = 0;
        for (float value : vector) {
            lengthSquared += value * value;
        }
        if (lengthSquared > 0) {
            float scale = (float) (1.0 / Math.sqrt(lengthSquared));
            for (int index = 0; index < vector.length; index++) {
                vector[index] *= scale;
            }
        }
        return vector;
    }

    private void addFeature(float[] vector, int feature) {
        int bucket = Math.floorMod(feature, dimensions);
        vector[bucket] += (feature & 0x10000) == 0 ? 1 : -1;
    }
}
