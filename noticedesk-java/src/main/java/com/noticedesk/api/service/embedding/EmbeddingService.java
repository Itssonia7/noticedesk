package com.noticedesk.api.service.embedding;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class EmbeddingService {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?\\n])\\s+");

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    public List<String> chunkText(String text, int maxTokens, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String cleaned = text.trim();
        int maxChars = maxTokens * 4;
        int overlapChars = overlap * 4;

        if (cleaned.length() <= maxChars) {
            return List.of(cleaned);
        }

        String[] sentences = SENTENCE_SPLIT.split(cleaned);
        List<String> chunks = new ArrayList<>();
        List<String> currentChunk = new ArrayList<>();
        int currentLength = 0;

        for (String sentence : sentences) {
            int sentenceLen = sentence.length();
            if (currentLength + sentenceLen > maxChars && !currentChunk.isEmpty()) {
                chunks.add(String.join(" ", currentChunk));

                List<String> overlapSentences = new ArrayList<>();
                int overlapLen = 0;
                for (int i = currentChunk.size() - 1; i >= 0; i--) {
                    String prev = currentChunk.get(i);
                    if (overlapLen + prev.length() <= overlapChars) {
                        overlapSentences.add(0, prev);
                        overlapLen += prev.length();
                    } else {
                        break;
                    }
                }

                currentChunk = overlapSentences;
                currentLength = overlapLen;
            }

            currentChunk.add(sentence);
            currentLength += sentenceLen;
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(String.join(" ", currentChunk));
        }

        return chunks;
    }
}
