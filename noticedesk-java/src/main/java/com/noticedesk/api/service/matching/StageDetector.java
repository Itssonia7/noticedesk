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
 *
 * <p>A triage label that names the SCN FORM or its ORIGIN rather than the proceeding ({@code DRC-01}, the summary form
 * of every Section 73 / 74 / 74A notice; {@code audit} / {@code ADT-01} when the SCN follows a Section 65 audit) is read
 * like an SCN label: the provision decides ({@code scn_73} / {@code scn_74} / {@code scn_74a}). Without a 73 / 74 / 74A
 * provision the label is kept as it is (a DRC-01 with no provision, a real ADT-01), and {@link StageTemplateAliases}
 * maps it to a template stage at assembly time.
 */
public final class StageDetector {

    public static final String UNKNOWN = "unknown";
    public static final String ANTI_PROFITEERING = "anti_profiteering";
    public static final String GENERIC_SCN = "generic_scn";

    private static final Pattern SEC_171 = Pattern.compile("(?i)\\b171\\b|anti[-\\s]?profiteering|\\brule\\s*133\\b");
    private static final Pattern SEC_74A = Pattern.compile("(?i)\\b74A\\b");
    private static final Pattern SEC_74 = Pattern.compile("(?i)\\b74\\b");
    private static final Pattern SEC_73 = Pattern.compile("(?i)\\b73\\b");
    /** Triage labels naming the SCN form (DRC-01, not DRC-01A) or its audit origin (audit, ADT-01). */
    private static final Pattern SCN_FORM_LABEL = Pattern.compile("(?i)^drc[\\s_\\-]*0?1$|^audit$|^adt[\\s_\\-]*0?1$");

    private StageDetector() {}

    public static String detect(String noticeSection, String triageDocumentType) {
        String triage = triageDocumentType != null ? triageDocumentType.trim().toLowerCase(Locale.ROOT) : "";
        String sec = noticeSection != null ? noticeSection.trim() : "";

        if (!sec.isEmpty() && SEC_171.matcher(sec).find()) return ANTI_PROFITEERING;
        if (triage.contains("profiteering")) return ANTI_PROFITEERING;

        boolean scnOrBlank = triage.isEmpty() || triage.startsWith("scn") || UNKNOWN.equals(triage);
        // a label naming the SCN form (DRC-01) or its audit origin: the provision decides the stage as for an SCN label
        boolean scnForm = SCN_FORM_LABEL.matcher(triage).find();
        if ((scnOrBlank || scnForm) && !sec.isEmpty()) {
            if (SEC_74A.matcher(sec).find()) return "scn_74a";
            if (SEC_74.matcher(sec).find()) return "scn_74";
            if (SEC_73.matcher(sec).find()) return "scn_73";
            // triage says show cause notice, but the provision is not 73/74/74A/171: never fall back to scn_73
            if (triage.startsWith("scn")) return GENERIC_SCN;
        }
        return triage.isEmpty() ? UNKNOWN : triage;
    }
}
