package com.noticedesk.api.service.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR on the gemini provider: the document bytes are sent inline with a transcription instruction and the model's
 * text is the OCR text. Pages are separated by {@code === PAGE n ===} lines (the page count is the number of those
 * markers, at least 1). Token usage comes back in {@link OcrResult#usage()}.
 */
@Slf4j
public class GeminiOcrProvider implements OcrProvider {

    static final String INSTRUCTION = """
            Transcribe this document exactly, page by page. Start every page with a line "=== PAGE n ===" (n = page \
            number from 1). Keep the original wording, numbers, dates, tables (one row per line, cells separated by \
            " | ") and paragraph numbering. Do not summarise, translate, correct or add anything. Output plain text only.""";

    private static final Pattern PAGE_MARKER = Pattern.compile("(?m)^=== PAGE \\d+ ===\\s*$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Client client;
    private final String model;
    private final int maxOutputTokens;

    public GeminiOcrProvider(String apiKey, String model, int maxOutputTokens, double timeoutSeconds) {
        if (model == null || model.isBlank()) throw new OcrException("gemini OCR: no model configured (OCR_GEMINI_MODEL)");
        this.model = model.trim();
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : 65536;
        int timeoutMs = (int) Math.round((timeoutSeconds > 0 ? timeoutSeconds : 300.0) * 1000);
        this.client = apiKey != null && !apiKey.isBlank()
                ? Client.builder().apiKey(apiKey).httpOptions(HttpOptions.builder().timeout(timeoutMs).build()).build()
                : null;
    }

    @Override
    public String getName() {
        return "gemini";
    }

    public String getModel() {
        return model;
    }

    @Override
    public OcrResult process(byte[] fileBytes, String filename, String mimeType) {
        if (client == null) throw new OcrException("gemini OCR: GEMINI_API_KEY is not set");
        if (fileBytes == null || fileBytes.length == 0) throw new OcrException("gemini OCR: empty document");
        String mime = mimeType != null && !mimeType.isBlank() ? mimeType : "application/pdf";
        GenerateContentConfig config = GenerateContentConfig.builder().maxOutputTokens(maxOutputTokens).temperature(0.0f).build();
        Content content = Content.fromParts(Part.fromBytes(fileBytes, mime), Part.fromText(INSTRUCTION));
        GenerateContentResponse response;
        try {
            response = client.models.generateContent(model, content, config);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
            if (msg.contains("429") || msg.contains("500") || msg.contains("502") || msg.contains("503")
                    || msg.contains("timeout") || msg.contains("resource_exhausted")) {
                throw new OcrTransientException("gemini OCR transient error: " + e.getMessage(), e);
            }
            throw new OcrException("gemini OCR error: " + e.getMessage(), e);
        }
        return toResult(response);
    }

    OcrResult toResult(GenerateContentResponse response) {
        String text;
        try {
            text = response.text();
        } catch (RuntimeException e) {
            text = null;
        }
        if (text == null || text.isBlank()) {
            String finish = response.candidates().flatMap(c -> c.isEmpty() ? Optional.empty() : c.get(0).finishReason()).orElse("none");
            throw new OcrException("gemini OCR returned no text (finish reason " + finish + ")");
        }
        Optional<GenerateContentResponseUsageMetadata> usage = response.usageMetadata();
        Integer in = usage.flatMap(GenerateContentResponseUsageMetadata::promptTokenCount).orElse(null);
        Integer cand = usage.flatMap(GenerateContentResponseUsageMetadata::candidatesTokenCount).orElse(null);
        Integer thoughts = usage.flatMap(GenerateContentResponseUsageMetadata::thoughtsTokenCount).orElse(null);
        // thinking tokens are billed as output
        Integer out = cand == null && thoughts == null ? null : (cand != null ? cand : 0) + (thoughts != null ? thoughts : 0);
        String tier = null;
        try {
            tier = tierInfo(response.toJson());
        } catch (RuntimeException e) {
            log.debug("gemini OCR: response not serialisable for the tier check: {}", e.getMessage());
        }
        return new OcrResult(text, pageCount(text), getName(), new OcrResult.OcrUsage(getName(), model, in, out, tier));
    }

    /** Number of {@code === PAGE n ===} markers in the text, at least 1. */
    static int pageCount(String text) {
        if (text == null) return 0;
        Matcher m = PAGE_MARKER.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return Math.max(n, 1);
    }

    /**
     * Every field of the response JSON whose name suggests the project or billing tier (tier, traffic, project,
     * billing, quota) as "path=value", comma-separated; null when there is none (the API reports no tier).
     */
    static String tierInfo(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(responseJson);
        } catch (Exception e) {
            return null;
        }
        List<String> found = new ArrayList<>();
        collect(root, "", found);
        return found.isEmpty() ? null : String.join(", ", found);
    }

    private static void collect(JsonNode node, String path, List<String> found) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                // never descend into the transcribed text
                if ("candidates".equals(key) || "content".equals(key)) continue;
                String p = path.isEmpty() ? key : path + "." + key;
                String k = key.toLowerCase(Locale.ROOT);
                if ((k.contains("tier") || k.contains("traffic") || k.contains("project") || k.contains("billing")
                        || k.contains("quota")) && e.getValue().isValueNode()) {
                    found.add(p + "=" + e.getValue().asText());
                } else {
                    collect(e.getValue(), p, found);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collect(node.get(i), path + "[" + i + "]", found);
        }
    }
}
