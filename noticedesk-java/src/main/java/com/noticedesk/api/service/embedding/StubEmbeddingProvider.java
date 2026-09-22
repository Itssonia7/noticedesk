package com.noticedesk.api.service.embedding;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

@Service
public class StubEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSION = 1536;

    @Override
    public List<Double> embedQuery(String text) {
        return generateVector(text);
    }

    @Override
    public List<List<Double>> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream().map(this::generateVector).toList();
    }

    private List<Double> generateVector(String text) {
        byte[] hash = sha256(text != null ? text : "");
        List<Double> vector = new ArrayList<>(DIMENSION);
        double sumSq = 0.0;

        for (int i = 0; i < DIMENSION; i++) {
            int b = hash[i % hash.length] & 0xFF;
            double val = ((b ^ ((i * 31) & 0xFF)) / 255.0) - 0.5;
            vector.add(val);
            sumSq += val * val;
        }

        double norm = Math.sqrt(sumSq);
        if (norm == 0.0) norm = 1.0;

        List<Double> normalized = new ArrayList<>(DIMENSION);
        for (Double val : vector) {
            normalized.add(val / norm);
        }
        return normalized;
    }

    private byte[] sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
