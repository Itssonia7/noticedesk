package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSON helpers shared by the v7 Stage 3 writers (partial, new-issue, high-stakes). */
final class AiOutputParser {

    private AiOutputParser() {}

    static Map<String, Object> readJsonObject(ObjectMapper objectMapper, String raw) throws Exception {
        String clean = raw.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        return objectMapper.readValue(clean, new TypeReference<>() {});
    }

    static List<RawAiCitation> citations(Map<String, Object> map) {
        List<RawAiCitation> citations = new ArrayList<>();
        if (map.get("citations") instanceof List<?> citList) {
            for (Object item : citList) {
                if (item instanceof Map<?, ?> m && m.get("case") != null && !m.get("case").toString().isBlank()) {
                    citations.add(new RawAiCitation(
                            m.get("case").toString().trim(),
                            str(m.get("court")),
                            parseYear(m.get("year")),
                            str(m.get("quoted_text")),
                            str(m.get("cited_for"))));
                }
            }
        }
        return citations;
    }

    static List<String> stringList(Map<String, Object> map, String key) {
        List<String> out = new ArrayList<>();
        if (map.get(key) instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString().trim());
                }
            }
        }
        return out;
    }

    static String str(Object o) {
        return (o == null || o.toString().isBlank()) ? null : o.toString().trim();
    }

    private static Integer parseYear(Object o) {
        if (o == null) return null;
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
