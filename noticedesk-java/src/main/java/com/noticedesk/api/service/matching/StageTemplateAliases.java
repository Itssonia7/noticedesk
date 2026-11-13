package com.noticedesk.api.service.matching;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps the stage key the pipeline detects for a notice ({@link StageDetector#detect}: a triage label such as
 * {@code DRC-01} or {@code audit}, or a provision-based key such as {@code scn_73}) to the id of the stage template
 * that answers it, and says which generic skeleton may stand in when that template does not exist.
 *
 * <p>Why: the stage templates are keyed by the PROCEEDING ({@code scn_73}, {@code scn_74}, {@code scn_74a},
 * {@code generic_scn}, {@code asmt_10}, {@code anti_profiteering}, {@code refund} ...) while triage labels the FORM
 * ({@code DRC-01}) or the ORIGIN ({@code audit}: a DRC-01 issued after a Section 65 audit). In the live run of
 * 13-11-2026 an ordinary DRC-01 under Section 73 after an audit was looked up as {@code drc-01}, found no template and
 * blocked the draft with {@code [[MISSING: stage template for drc-01]]}.
 *
 * <p>Alias table (keys normalised: lower case, {@code -} and spaces read as {@code _}, a leading zero in a form number
 * dropped):
 * <table>
 *   <tr><th>detector / triage key</th><th>template stage</th></tr>
 *   <tr><td>{@code scn_73}</td><td>{@code scn_73}</td></tr>
 *   <tr><td>{@code scn_74}</td><td>{@code scn_74}</td></tr>
 *   <tr><td>{@code scn_74a}</td><td>{@code scn_74a}</td></tr>
 *   <tr><td>{@code scn}, {@code generic_scn}</td><td>{@code generic_scn}</td></tr>
 *   <tr><td>{@code drc_01} ({@code drc-01}, {@code drc01}, {@code DRC 1})</td>
 *       <td>by the notice provision: 74A -> {@code scn_74a}, 74 -> {@code scn_74}, 73 -> {@code scn_73}; no provision
 *       (or another one) -> {@code generic_scn}</td></tr>
 *   <tr><td>{@code audit}, {@code adt_01} (SCN issued after a Section 65 audit)</td>
 *       <td>by the notice provision as for {@code drc_01}; a Section 65 / provision-less audit document stays
 *       {@code audit} (an ADT-01 is not a show cause notice)</td></tr>
 *   <tr><td>anything else ({@code asmt_10}, {@code rfd_08}, {@code refund}, {@code anti_profiteering}, {@code appeal} ...)</td>
 *       <td>itself</td></tr>
 * </table>
 *
 * <p>Fallback: for a show-cause stage ({@link #SCN_FAMILY}: the scn keys, {@code drc_01}, {@code audit}) whose exact
 * template is missing, the {@code generic_scn} skeleton (neutral wording: no Section 73 / 74 reference) is used with
 * the flag {@code warn:stage_template_fallback} instead of the block. Every other stage keeps the (b18) rule: a
 * placeholder and {@code block:stage_template_missing} (an appeal or a refund rejection must not get an SCN prayer).
 */
public final class StageTemplateAliases {

    /** Flag (warn) when the generic SCN skeleton stands in for a missing stage template. */
    public static final String FLAG_FALLBACK = "warn:stage_template_fallback";
    /** The stage whose template is the fallback skeleton. */
    public static final String FALLBACK_STAGE = StageDetector.GENERIC_SCN;

    /** Fixed aliases (normalised key -> template stage). The provision-dependent keys are in {@link #SCN_FORM_KEYS}. */
    static final Map<String, String> ALIASES = Map.of(
            "scn", StageDetector.GENERIC_SCN,
            "generic_scn", StageDetector.GENERIC_SCN,
            "scn_73", "scn_73",
            "scn_74", "scn_74",
            "scn_74a", "scn_74a");

    /** Keys naming the SCN form or its origin, not the proceeding: the provision decides the template stage. */
    static final Set<String> SCN_FORM_KEYS = Set.of("drc_1", "audit", "adt_1");

    /** Stages for which the generic SCN skeleton may stand in (after aliasing). */
    static final Set<String> SCN_FAMILY = Set.of("scn_73", "scn_74", "scn_74a", StageDetector.GENERIC_SCN, "drc_1", "audit", "adt_1");

    private static final Pattern SEC_74A = Pattern.compile("(?i)\\b74A\\b");
    private static final Pattern SEC_74 = Pattern.compile("(?i)\\b74\\b");
    private static final Pattern SEC_73 = Pattern.compile("(?i)\\b73\\b");

    /**
     * The outcome of the alias step.
     *
     * @param detectorKey   the key as detected (trimmed, lower case)
     * @param templateStage the stage id the template is looked up under
     * @param aliased       true when it differs from the detector key
     * @param fallbackStage the skeleton to use when no template exists for {@code templateStage}, or null (block)
     */
    public record Resolution(String detectorKey, String templateStage, boolean aliased, String fallbackStage) {
        /** "scn_73 (alias of drc_01)" / "scn_73" for the partner note. */
        public String describe() {
            return aliased ? templateStage + " (alias of " + detectorKey + ")" : templateStage;
        }
    }

    private StageTemplateAliases() {}

    /**
     * The form used to look a key up in the tables (the key itself is never rewritten: a stage such as {@code rfd_08}
     * is looked up under its own spelling): lower case, separators to {@code _}, a leading zero in a trailing form
     * number dropped: "DRC-01" / "drc01" / "drc_01" -> "drc_1", "ADT 01" -> "adt_1".
     */
    static String normalise(String key) {
        if (key == null || key.isBlank()) return StageDetector.UNKNOWN;
        String k = key.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-]+", "_");
        k = k.replaceAll("(?<=[a-z])(?=\\d)", "_").replaceAll("_+", "_");   // drc01 -> drc_01
        return k.replaceAll("_0+(\\d)", "_$1");                              // drc_01 -> drc_1, adt_01 -> adt_1
    }

    /**
     * The template stage for {@code detectorKey} and the notice's provision ({@code noticeSection}: "Section 73",
     * "Section 74(1)" ..., may be null).
     */
    public static Resolution resolve(String detectorKey, String noticeSection) {
        String key = detectorKey == null || detectorKey.isBlank() ? StageDetector.UNKNOWN : detectorKey.trim().toLowerCase(Locale.ROOT);
        String lookup = normalise(key);
        String stage;
        if (SCN_FORM_KEYS.contains(lookup)) {
            String byProvision = scnStageFor(noticeSection);
            // an audit document under no 73/74 provision is an ADT-01, not an SCN: no alias (and the fallback applies)
            stage = byProvision != null ? byProvision : lookup.startsWith("drc") ? StageDetector.GENERIC_SCN : key;
        } else {
            stage = ALIASES.getOrDefault(lookup, key);
        }
        boolean scnFamily = SCN_FAMILY.contains(lookup) || SCN_FAMILY.contains(stage);
        // the generic skeleton itself has nothing to fall back to
        String fallback = scnFamily && !FALLBACK_STAGE.equals(stage) ? FALLBACK_STAGE : null;
        return new Resolution(key, stage, !stage.equals(key), fallback);
    }

    /** True when the generic skeleton may stand in for a missing template of this (detected or aliased) stage. */
    public static boolean fallbackAllowed(String detectorKey, String noticeSection) {
        return resolve(detectorKey, noticeSection).fallbackStage() != null;
    }

    /** The scn stage the provision names (74A before 74 before 73), or null. */
    static String scnStageFor(String noticeSection) {
        if (noticeSection == null || noticeSection.isBlank()) return null;
        if (SEC_74A.matcher(noticeSection).find()) return "scn_74a";
        if (SEC_74.matcher(noticeSection).find()) return "scn_74";
        if (SEC_73.matcher(noticeSection).find()) return "scn_73";
        return null;
    }

    /** The alias table as lines for documentation and the dry-run tools. */
    public static List<String> describeTable() {
        return List.of(
                "scn_73 -> scn_73", "scn_74 -> scn_74", "scn_74a -> scn_74a", "scn / generic_scn -> generic_scn",
                "drc_01 (DRC-01, drc01) -> scn_74a / scn_74 / scn_73 by the provision, else generic_scn",
                "audit / adt_01 -> scn_74a / scn_74 / scn_73 by the provision, else audit (an ADT-01 is not an SCN)",
                "other keys -> themselves",
                "fallback for the SCN family (scn_*, generic_scn, drc_01, audit): generic_scn + " + FLAG_FALLBACK
                        + "; other stages: block:stage_template_missing");
    }
}
