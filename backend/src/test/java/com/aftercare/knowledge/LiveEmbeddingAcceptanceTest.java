package com.aftercare.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.aftercare.AftersalesApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        classes = AftersalesApplication.class,
        webEnvironment = WebEnvironment.MOCK,
        properties = "app.demo-seed.enabled=false")
@ActiveProfiles({"live", "deepseek"})
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_MODEL_ACCEPTANCE", matches = "true")
class LiveEmbeddingAcceptanceTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void qwenEmbeddingReturns1024FiniteNonZeroValues() {
        float[] vector = embeddingModel.embed("Synthetic after-sales embedding verification.");

        assertThat(vector).hasSize(1024);
        boolean hasNonZeroValue = false;
        for (float value : vector) {
            assertThat(Float.isFinite(value)).isTrue();
            hasNonZeroValue |= value != 0.0f;
        }
        assertThat(hasNonZeroValue).isTrue();
    }
}
