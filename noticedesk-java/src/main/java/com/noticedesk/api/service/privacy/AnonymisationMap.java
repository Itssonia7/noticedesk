package com.noticedesk.api.service.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The reversible mapping of one anonymisation: every placeholder the anonymised text carries and the real value it
 * stands for, with the kind of value (gstin, pan, taxpayer_name, ...). The mapping holds the client's real
 * identifiers, so it is written only under {@code $PRIVATE_SEED_DIR/results/} (never in the repository) and is never
 * logged; {@link #summaryLine()} gives counts only.
 */
public final class AnonymisationMap {

    public static final String KIND_GSTIN = "gstin";
    public static final String KIND_PAN = "pan";
    public static final String KIND_TAN = "tan";
    public static final String KIND_CIN = "cin";
    public static final String KIND_IEC = "iec";
    public static final String KIND_AADHAAR = "aadhaar";
    public static final String KIND_ACCOUNT = "bank_account";
    public static final String KIND_IFSC = "ifsc";
    public static final String KIND_PHONE = "phone";
    public static final String KIND_EMAIL = "email";
    public static final String KIND_PIN = "pin_code";
    public static final String KIND_REFERENCE = "reference";
    public static final String KIND_TAXPAYER_NAME = "taxpayer_name";
    public static final String KIND_PARTY_NAME = "party_name";
    public static final String KIND_PERSON_NAME = "person_name";
    public static final String KIND_ADDRESS = "address";

    /** One replacement: {@code placeholder} stands for {@code value} (the text exactly as it appeared). */
    public record Entry(String kind, String placeholder, String value) {}

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byPlaceholder = new LinkedHashMap<>();
    private final Map<String, String> byPlaceholderLower = new LinkedHashMap<>();

    /** Adds a placeholder; a placeholder already present keeps its first value (the mapping stays a function). */
    void add(String kind, String placeholder, String value) {
        if (byPlaceholder.containsKey(placeholder)) return;
        Entry e = new Entry(kind, placeholder, value);
        entries.add(e);
        byPlaceholder.put(placeholder, e);
        byPlaceholderLower.putIfAbsent(placeholder.toLowerCase(Locale.ROOT), placeholder);
    }

    boolean hasPlaceholder(String placeholder) {
        return byPlaceholder.containsKey(placeholder);
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** The real value of a placeholder: exact match first, else the first entry equal ignoring case; null when none. */
    public String valueOf(String placeholder) {
        Entry e = byPlaceholder.get(placeholder);
        if (e == null) {
            String exact = byPlaceholderLower.get(placeholder.toLowerCase(Locale.ROOT));
            e = exact != null ? byPlaceholder.get(exact) : null;
        }
        return e != null ? e.value() : null;
    }

    /**
     * Distinct real values per kind (case-insensitive), e.g. two spellings of one name count once; a GSTIN or PAN
     * split over a line break counts once with its joined form.
     */
    public Map<String, Integer> counts() {
        Map<String, java.util.Set<String>> distinct = new TreeMap<>();
        for (Entry e : entries) {
            String v = e.value().toLowerCase(Locale.ROOT);
            if (KIND_GSTIN.equals(e.kind()) || KIND_PAN.equals(e.kind())) v = v.replaceAll("[\\s\\-]", "");
            distinct.computeIfAbsent(e.kind(), k -> new java.util.HashSet<>()).add(v);
        }
        Map<String, Integer> out = new TreeMap<>();
        distinct.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    public int count(String kind) {
        return counts().getOrDefault(kind, 0);
    }

    /**
     * "GSTINs 3, PANs 1, names 4 (taxpayer 1, other parties 2, persons 1), addresses 2, references 2, other ids 3":
     * counts only, never a value.
     */
    public String summaryLine() {
        Map<String, Integer> c = counts();
        int names = c.getOrDefault(KIND_TAXPAYER_NAME, 0) + c.getOrDefault(KIND_PARTY_NAME, 0) + c.getOrDefault(KIND_PERSON_NAME, 0);
        int otherIds = 0;
        for (String k : List.of(KIND_TAN, KIND_CIN, KIND_IEC, KIND_AADHAAR, KIND_ACCOUNT, KIND_IFSC, KIND_PHONE, KIND_EMAIL, KIND_PIN)) {
            otherIds += c.getOrDefault(k, 0);
        }
        return "GSTINs " + c.getOrDefault(KIND_GSTIN, 0) + ", PANs " + c.getOrDefault(KIND_PAN, 0)
                + ", names " + names + " (taxpayer " + c.getOrDefault(KIND_TAXPAYER_NAME, 0) + ", other parties "
                + c.getOrDefault(KIND_PARTY_NAME, 0) + ", persons " + c.getOrDefault(KIND_PERSON_NAME, 0) + ")"
                + ", addresses " + c.getOrDefault(KIND_ADDRESS, 0) + ", references " + c.getOrDefault(KIND_REFERENCE, 0)
                + ", other ids " + otherIds;
    }

    // ---------------------------------------------------------------- JSON

    /** JSON shape: {"version":1, "counts":{...}, "entries":[{"kind","placeholder","value"}, ...]}. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 1);
        out.put("counts", counts());
        List<Map<String, String>> list = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("kind", e.kind());
            m.put("placeholder", e.placeholder());
            m.put("value", e.value());
            list.add(m);
        }
        out.put("entries", list);
        return out;
    }

    public static AnonymisationMap fromJson(Map<String, Object> json) {
        AnonymisationMap m = new AnonymisationMap();
        if (json == null || !(json.get("entries") instanceof Collection<?> list)) return m;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> e)) continue;
            Object ph = e.get("placeholder");
            Object v = e.get("value");
            if (ph == null || v == null) continue;
            Object kind = e.get("kind");
            m.add(kind != null ? kind.toString() : "unknown", ph.toString(), v.toString());
        }
        return m;
    }

    public void writeTo(File file, ObjectMapper om) throws IOException {
        om.writerWithDefaultPrettyPrinter().writeValue(file, toJson());
    }

    @SuppressWarnings("unchecked")
    public static AnonymisationMap readFrom(File file, ObjectMapper om) throws IOException {
        return fromJson(om.readValue(file, Map.class));
    }
}
