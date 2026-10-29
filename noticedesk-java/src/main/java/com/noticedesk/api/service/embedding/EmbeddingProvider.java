package com.noticedesk.api.service.embedding;

import java.util.List;

public interface EmbeddingProvider {
    List<Double> embedQuery(String text);
    List<List<Double>> embedDocuments(List<String> texts);
}
