package com.noticedesk.api.agent;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JSON helpers shared by the v7 Stage 3 writers (partial, new-issue, high-stakes). */
final class AiOutputParser {

    private AiOutputParser() {}

    private static final int MAX_QUOTE_REPAIRS = 200;

    /**
     * Reads the writer's JSON object from a raw response: markdown fences and prose before/after the
     * object are dropped, the outermost {...} is taken, and double quotes left unescaped inside string
     * values (e.g. {@code "html": "<p>the so-called "input" tax</p>"}) are escaped.
     *
     * @throws IllegalArgumentException with a short message (no response content) when no JSON object can be read
     */
    static Map<String, Object> readJsonObject(ObjectMapper objectMapper, String raw) {
        return objectMapper.convertValue(readJsonTree(objectMapper, raw), new TypeReference<Map<String, Object>>() {});
    }

    /** As {@link #readJsonObject}, as a tree (used by the matcher). */
    static JsonNode readJsonTree(ObjectMapper objectMapper, String raw) {
        String json = extractJsonObject(raw);
        JsonProcessingException first = null;
        for (int i = 0; i <= MAX_QUOTE_REPAIRS; i++) {
            try {
                return objectMapper.readTree(json);
            } catch (JsonProcessingException e) {
                if (first == null) first = e;
                String repaired = escapeStrayQuote(json, e);
                if (repaired == null) break;
                json = repaired;
            }
        }
        JsonLocation loc = first.getLocation();
        throw new IllegalArgumentException("invalid JSON" + (loc != null ? " at line " + loc.getLineNr() + " column " + loc.getColumnNr() : "")
                + ": " + first.getOriginalMessage());
    }

    /** The outermost JSON object in {@code raw} (inside a ```json fence when there is one). */
    static String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty response");
        String s = raw.replace("\uFEFF", "");
        Matcher fence = FENCE.matcher(s);
        while (fence.find()) {
            if (fence.group(1).contains("{")) {
                s = fence.group(1);
                break;
            }
        }
        int start = s.indexOf('{');
        if (start < 0) throw new IllegalArgumentException("no JSON object found in the response");
        int end = matchingBrace(s, start);
        if (end < 0) end = s.lastIndexOf('}');
        if (end <= start) throw new IllegalArgumentException("JSON object is not closed (response cut off?)");
        return s.substring(start, end + 1);
    }

    private static final Pattern FENCE = Pattern.compile("```[a-zA-Z]*\\s*(.*?)```", Pattern.DOTALL);

    /** Index of the brace closing the object opened at {@code start}, string-aware; -1 when unbalanced. */
    private static int matchingBrace(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * When the parser stopped right after a string that ended early (an unescaped quote inside the value),
     * escapes that quote and returns the new text; null when the error is of another kind.
     */
    private static String escapeStrayQuote(String json, JsonProcessingException e) {
        JsonLocation loc = e.getLocation();
        if (loc == null) return null;
        int offset = (int) loc.getCharOffset();
        if (offset <= 0 || offset > json.length()) return null;
        int i = Math.min(offset, json.length()) - 1;
        char at = json.charAt(i);
        if (!Character.isWhitespace(at) && at != '"' && at != ',') i--;   // the location is just past the unexpected char
        while (i >= 0 && Character.isWhitespace(json.charAt(i))) i--;
        if (i >= 0 && json.charAt(i) == ',') {            // "... "yes", then ..." parsed as a field separator
            i--;
            while (i >= 0 && Character.isWhitespace(json.charAt(i))) i--;
        }
        if (i <= 0 || json.charAt(i) != '"' || json.charAt(i - 1) == '\\') return null;
        return json.substring(0, i) + "\\" + json.substring(i);
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

    /**
     * The writer's {@code assumed_facts}: objects {@code {"fact": ..., "sentence": ...}} (a plain string is a fact
     * without its sentence). Entries without a fact are dropped; the fact text is trimmed of a [[CONFIRM: ...]] wrapper.
     */
    static List<AssumedFact> assumedFacts(Map<String, Object> map) {
        List<AssumedFact> out = new ArrayList<>();
        if (map.get("assumed_facts") instanceof List<?> list) {
            for (Object o : list) {
                String fact = null;
                String sentence = null;
                if (o instanceof Map<?, ?> m) {
                    fact = str(m.get("fact"));
                    sentence = str(m.get("sentence"));
                } else if (o != null) {
                    fact = str(o);
                }
                if (fact == null) continue;
                fact = fact.replaceAll("^\\[\\[\\s*CONFIRM:\\s*|\\s*\\]\\]$", "").trim();
                if (!fact.isEmpty()) out.add(new AssumedFact(fact, sentence));
            }
        }
        return out;
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
