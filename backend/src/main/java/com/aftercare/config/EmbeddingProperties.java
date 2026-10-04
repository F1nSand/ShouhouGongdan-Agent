package com.aftercare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(int dimensions) {

    public EmbeddingProperties {
        if (dimensions != 1024) {
            throw new IllegalArgumentException(
                    "app.embedding.dimensions must be 1024, but was " + dimensions);
        }
    }
}
