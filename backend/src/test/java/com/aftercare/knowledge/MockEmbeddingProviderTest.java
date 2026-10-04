package com.aftercare.knowledge;

import com.aftercare.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockEmbeddingProviderTest {
    @Test
    void createsStableNormalizedVectorsAtTheConfiguredDimension() {
        var properties = new EmbeddingProperties(1024);
        var provider = new MockEmbeddingProvider(properties, "mock-char-ngram-v1");

        float[] first = provider.embedQuery("退货政策 七日内");
        float[] second = provider.embedDocuments(java.util.List.of("退货政策 七日内")).getFirst();
        double norm = 0;
        for (float value : first) {
            norm += value * value;
        }

        assertThat(first).hasSize(1024).containsExactly(second);
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.00001));
        assertThat(provider.modelName()).isEqualTo("mock-char-ngram-v1");
    }
}
