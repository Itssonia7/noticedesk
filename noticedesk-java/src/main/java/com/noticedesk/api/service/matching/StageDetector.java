package com.noticedesk.api.service.matching;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chooses the stage template for a notice. The provision the notice is issued under (from the notice
 * record) wins over the triage document type for show cause notices, because triage labels every SCN
 * "scn_73": a Section 171 notice is {@code anti_profiteering}, Section 74A {@code scn_74a}, Section 74
 * {@code scn_74}, Section 73 {@code scn_73}. Other triage types (asmt_10, rfd_08, drc_01a, appeal...)
 * are kept. A notice triaged as a show cause notice but issued under some other provision (e.g.
 * Section 27 / 63 / 129) gets {@code generic_scn}, whose stage template cites no Section 73/74 wording.
 * With nothing to go on the stage is {@code unknown} (never a silent scn_73).
 */
public final class StageDetector {

    public static final String UNKNOWN = "unknown";
    public static final String ANTI_PROFITEERING = "anti_profiteering";
    public static final String GENERIC_SCN = "generic_scn";

    private static final Pattern SEC_171 = Pattern.compile("(?i)\\b171\\b|anti[-\\s]?profiteering|\\brule\\s*133\\b");
    private static final Pattern SEC_74A = Pattern.compile("(?i)\\b74A\\b");
    private static final Pattern SEC_74 = Pattern.compile("(?i)\\b74\\b");
    private static final Pattern SEC_73 = Pattern.compile("(?i)\\b73\\b");

    private StageDetector() {}

    public static String detect(String noticeSection, String triageDocumentType) {
        String triage = triageDocumentType != null ? triageDocumentType.trim().toLowerCase(Locale.ROOT) : "";
        String sec = noticeSection != null ? noticeSection.trim() : "";

        if (!sec.isEmpty() && SEC_171.matcher(sec).find()) return ANTI_PROFITEERING;
        if (triage.contains("profiteering")) return ANTI_PROFITEERING;

        boolean scnOrBlank = triage.isEmpty() || triage.startsWith("scn") || UNKNOWN.equals(triage);
        if (scnOrBlank && !sec.isEmpty()) {
            if (SEC_74A.matcher(sec).find()) return "scn_74a";
            if (SEC_74.matcher(sec).find()) return "scn_74";
            if (SEC_73.matcher(sec).find()) return "scn_73";
            // triage says show cause notice, but the provision is not 73/74/74A/171: never fall back to scn_73
            if (triage.startsWith("scn")) return GENERIC_SCN;
        }
        return triage.isEmpty() ? UNKNOWN : triage;
    }
}
