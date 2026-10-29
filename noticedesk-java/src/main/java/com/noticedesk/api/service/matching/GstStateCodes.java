package com.noticedesk.api.service.matching;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * GST state codes (the first two digits of a GSTIN) to State / Union Territory names, as used on the
 * GST portal. Code 25 (former UT of Daman and Diu) and code 26 (former UT of Dadra and Nagar Haveli)
 * both belong to the merged UT of Dadra and Nagar Haveli and Daman and Diu since 26-01-2020; existing
 * code-25 registrations continue under the merged UT. Code 28 is the pre-bifurcation Andhra Pradesh
 * code still carried by older registrations; 37 is the current Andhra Pradesh code.
 * An unknown code returns empty: callers print a [[MISSING: state]] marker, never a made-up name.
 */
public final class GstStateCodes {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("01", "Jammu and Kashmir"),
            Map.entry("02", "Himachal Pradesh"),
            Map.entry("03", "Punjab"),
            Map.entry("04", "Chandigarh"),
            Map.entry("05", "Uttarakhand"),
            Map.entry("06", "Haryana"),
            Map.entry("07", "Delhi"),
            Map.entry("08", "Rajasthan"),
            Map.entry("09", "Uttar Pradesh"),
            Map.entry("10", "Bihar"),
            Map.entry("11", "Sikkim"),
            Map.entry("12", "Arunachal Pradesh"),
            Map.entry("13", "Nagaland"),
            Map.entry("14", "Manipur"),
            Map.entry("15", "Mizoram"),
            Map.entry("16", "Tripura"),
            Map.entry("17", "Meghalaya"),
            Map.entry("18", "Assam"),
            Map.entry("19", "West Bengal"),
            Map.entry("20", "Jharkhand"),
            Map.entry("21", "Odisha"),
            Map.entry("22", "Chhattisgarh"),
            Map.entry("23", "Madhya Pradesh"),
            Map.entry("24", "Gujarat"),
            Map.entry("25", "Dadra and Nagar Haveli and Daman and Diu"),
            Map.entry("26", "Dadra and Nagar Haveli and Daman and Diu"),
            Map.entry("27", "Maharashtra"),
            Map.entry("28", "Andhra Pradesh"),
            Map.entry("29", "Karnataka"),
            Map.entry("30", "Goa"),
            Map.entry("31", "Lakshadweep"),
            Map.entry("32", "Kerala"),
            Map.entry("33", "Tamil Nadu"),
            Map.entry("34", "Puducherry"),
            Map.entry("35", "Andaman and Nicobar Islands"),
            Map.entry("36", "Telangana"),
            Map.entry("37", "Andhra Pradesh"),
            Map.entry("38", "Ladakh"),
            Map.entry("97", "Other Territory"),
            Map.entry("99", "Centre Jurisdiction"));

    private GstStateCodes() {}

    /** Name for a two-digit state code; empty when the code is not a GST state code. */
    public static Optional<String> nameForCode(String code) {
        if (code == null) return Optional.empty();
        return Optional.ofNullable(NAMES.get(code.trim()));
    }

    /** Name for the state code at the start of a GSTIN; empty when the GSTIN is short or the code unknown. */
    public static Optional<String> nameForGstin(String gstin) {
        if (gstin == null) return Optional.empty();
        String g = gstin.trim();
        if (g.length() < 2 || !Character.isDigit(g.charAt(0)) || !Character.isDigit(g.charAt(1))) return Optional.empty();
        return nameForCode(g.substring(0, 2));
    }

    /**
     * True for a blank or placeholder state name ("Unknown State", "unknown", "N/A", "null"), and for the codes that are
     * not a State / UT ("Centre Jurisdiction", "Other Territory"): a draft never says "State of Centre Jurisdiction".
     */
    public static boolean isPlaceholderName(String name) {
        if (name == null || name.isBlank()) return true;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.startsWith("unknown") || n.equals("n/a") || n.equals("na") || n.equals("null") || n.equals("-")
                || n.matches("cent(?:re|er)\\s+jurisdiction|other\\s+territory");
    }
}
