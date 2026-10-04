package com.aftercare.knowledge;

import java.util.List;

public interface EmbeddingProvider {
    float[] embedQuery(String text);

    List<float[]> embedDocuments(List<String> texts);

    String modelName();
}
