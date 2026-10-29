package com.noticedesk.api.service.matching;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The RESPONSE the firm drafts to the department's input document, decided by code from the document type, the form
 * name and the heading, per the same input-document to response-stage table as the matching prompt (DRC-01, DRC-01A,
 * ASMT-10, ADT-01, REG-17, RFD-08, MOV-07, summons, Section 46 / 62 intimation -> reply / representation; DRC-22 ->
 * objection; DRC-13, recovery letter -> representation; DRC-07, RFD-06, REG-05, MOV-09, order-in-original,
 * order-in-appeal -> appeal; REG-19 -> revocation, or appeal). Used after matching to correct a primary card of the
 * wrong response stage; an input it cannot place resolves to null (no correction).
 */
public final class StageResolver {

    /** Response stage the input document calls for. */
    public enum ResponseStage {
        REPLY, APPEAL, REVOCATION, OBJECTION, REPRESENTATION;

        /** Lower-case name for flags and notes ("appeal", "reply", ...). */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the department's document is, for the notice wording ("Show Cause Notice" only for an SCN). */
    public enum DocumentKind {
        SCN("Show Cause Notice"), ORDER("Order"), INTIMATION("Intimation"), LETTER("Letter"), NOTICE("Notice");

        private final String label;

        DocumentKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** One row of the table: a form name / heading pattern, its response stage and document kind. */
    record Rule(Pattern pattern, ResponseStage stage, DocumentKind kind) {}

    private static Rule rule(String regex, ResponseStage stage, DocumentKind kind) {
        return new Rule(Pattern.compile("(?i)" + regex), stage, kind);
    }

    /** Form-name separator: "DRC-07", "DRC 07", "DRC_07", "DRC07". */
    private static final String SEP = "[\\s_\\-]*";

    static final List<Rule> RULES = List.of(
            // orders -> appeal
            rule("\\bDRC" + SEP + "0?7\\b", ResponseStage.APPEAL, DocumentKind.ORDER),
            rule("\\bRFD" + SEP + "0?6\\b", ResponseStage.APPEAL, DocumentKind.ORDER),
            rule("\\bREG" + SEP + "0?5\\b", ResponseStage.APPEAL, DocumentKind.ORDER),
            rule("\\bMOV" + SEP + "0?9\\b", ResponseStage.APPEAL, DocumentKind.ORDER),
            rule("\\border" + SEP + "in" + SEP + "original\\b|\\bOIO\\b", ResponseStage.APPEAL, DocumentKind.ORDER),
            rule("\\border" + SEP + "in" + SEP + "appeal\\b|\\bOIA\\b", ResponseStage.APPEAL, DocumentKind.ORDER),
            // cancellation order -> revocation application (or appeal): no card correction either way
            rule("\\bREG" + SEP + "19\\b", ResponseStage.REVOCATION, DocumentKind.ORDER),
            // notices / intimations -> reply
            rule("\\bDRC" + SEP + "0?1A\\b", ResponseStage.REPLY, DocumentKind.INTIMATION),
            rule("\\bDRC" + SEP + "0?1\\b", ResponseStage.REPLY, DocumentKind.SCN),
            rule("\\bASMT" + SEP + "10\\b", ResponseStage.REPLY, DocumentKind.INTIMATION),
            rule("\\bADT" + SEP + "0?1\\b", ResponseStage.REPLY, DocumentKind.NOTICE),
            rule("\\bREG" + SEP + "17\\b", ResponseStage.REPLY, DocumentKind.SCN),
            rule("\\bRFD" + SEP + "0?8\\b", ResponseStage.REPLY, DocumentKind.SCN),
            rule("\\bMOV" + SEP + "0?7\\b", ResponseStage.REPLY, DocumentKind.SCN),
            rule("\\bsummons\\b", ResponseStage.REPLY, DocumentKind.NOTICE),
            rule("\\bsection\\s*46\\b|\\bGSTR" + SEP + "3A\\b", ResponseStage.REPLY, DocumentKind.NOTICE),
            rule("\\bsection\\s*62\\b|\\bASMT" + SEP + "13\\b", ResponseStage.REPRESENTATION, DocumentKind.ORDER),
            // attachment / recovery -> objection / representation
            rule("\\bDRC" + SEP + "22\\b", ResponseStage.OBJECTION, DocumentKind.ORDER),
            rule("\\bDRC" + SEP + "13\\b", ResponseStage.REPRESENTATION, DocumentKind.NOTICE),
            rule("\\brecovery\\s+letter\\b", ResponseStage.REPRESENTATION, DocumentKind.LETTER),
            // a show cause notice by name (or the triage label scn_73 / scn_74) -> reply
            rule("\\bshow\\s+cause\\s+notice\\b|^scn\\b|^scn_", ResponseStage.REPLY, DocumentKind.SCN)
    );

    /** Issue flag prefix of a stage correction: {@code "stage_corrected: <old> -> <new>"}. */
    public static final String ISSUE_FLAG_STAGE_CORRECTED = "stage_corrected";
    /** Issue flag prefix of an uncorrected stage mismatch: {@code "stage_mismatch: <input stage> vs <card stage>"}. */
    public static final String ISSUE_FLAG_STAGE_MISMATCH = "stage_mismatch";

    /** Characters of the document head read for the form name and heading. */
    static final int HEADING_CHARS = 1500;

    private StageResolver() {}

    /**
     * The response stage for the input document, or null when neither the document type nor the heading names a
     * form or document in the table. A specific document type (a form name) wins; a generic one (the triage label
     * scn_73, unknown, needs_review) yields to the form name that occurs first in the heading, and is used only when
     * the heading names none.
     */
    public static ResponseStage resolve(String documentType, String documentText) {
        Rule r = resolveRule(documentType, documentText);
        return r != null ? r.stage() : null;
    }

    /** The document kind on the same reading as {@link #resolve}, or null when unknown. */
    public static DocumentKind documentKind(String documentType, String documentText) {
        Rule r = resolveRule(documentType, documentText);
        return r != null ? r.kind() : null;
    }

    static Rule resolveRule(String documentType, String documentText) {
        String type = documentType != null ? documentType.trim() : "";
        Rule fromType = type.isEmpty() ? null : firstRule(type);
        if (fromType != null && fromType.kind() != DocumentKind.SCN) return fromType;
        if (fromType != null && !isGenericScnLabel(type)) return fromType;   // DRC-01 / REG-17 / RFD-08 / MOV-07
        String head = documentText == null ? "" : documentText.substring(0, Math.min(HEADING_CHARS, documentText.length()));
        Rule fromHeading = head.isBlank() ? null : firstRule(head);
        return fromHeading != null ? fromHeading : fromType;
    }

    /** The triage label every SCN gets (scn_73, scn_74, scn): it does not name the form, so the heading may override it. */
    private static boolean isGenericScnLabel(String type) {
        return type.toLowerCase(Locale.ROOT).startsWith("scn");
    }

    /** The rule whose pattern occurs earliest in {@code text} (ties: table order). */
    private static Rule firstRule(String text) {
        Rule best = null;
        int bestAt = Integer.MAX_VALUE;
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(text);
            if (m.find() && m.start() < bestAt) {
                best = r;
                bestAt = m.start();
            }
        }
        return best;
    }
}
