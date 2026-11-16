package com.noticedesk.api.service.matching;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The kind of value a template variable expects, and whether a value fits it, checked before the value is substituted
 * into a sentence. The live run on the synthetic Section 73 notice read {@code issue.late_fee_status} as "17800" and
 * printed "late fee under Section 47 — which the noticee 17800": a status is text, never a bare amount.
 *
 * <p>Templates declare variable names only (no type), so the kind is taken from a declared type when one is given
 * ({@link #kindOf(String, String)}), else from the name's last part: {@code _status}, {@code _position},
 * {@code _exception}, {@code _type}, {@code _reason}, {@code _basis}, {@code _plea}, {@code _nature} are TEXT;
 * {@code _amount}, {@code _value}, {@code _tax} AMOUNT; {@code _date} DATE; {@code _no}, {@code _number} IDENTIFIER;
 * anything else ANY (not checked).</p>
 */
public final class TemplateVariableKinds {

    private TemplateVariableKinds() {}

    public enum Kind { TEXT, AMOUNT, DATE, IDENTIFIER, ANY;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final List<String> TEXT_ENDINGS = List.of("status", "position", "exception", "type", "reason", "basis",
            "plea", "nature");
    private static final List<String> AMOUNT_ENDINGS = List.of("amount", "value", "tax");
    private static final List<String> IDENTIFIER_ENDINGS = List.of("no", "number");

    /** "17800", "Rs. 17,800/-", "INR 1,10,957.00", "₹ 500": a number or rupee amount and nothing else. */
    private static final Pattern BARE_AMOUNT = Pattern.compile(
            "(?i)^\\s*(?:(?:Rs\\.?|INR|₹)\\s*)?[-+]?\\d[\\d,]*(?:\\.\\d+)?\\s*(?:/-)?\\s*(?:(?:lakh|lakhs|crore|crores)\\s*)?$");
    private static final Pattern DATE_LIKE = Pattern.compile(
            "(?i)\\b\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b"
            + "|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*,?\\s+\\d{4}\\b"
            + "|\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}\\b");

    /** The kind implied by the variable's name ("issue.late_fee_status" -> TEXT). */
    public static Kind kindOf(String variableName) {
        return kindOf(variableName, null);
    }

    /**
     * The declared kind when {@code declared} names one (text, amount, date, identifier / id, any), else the kind
     * implied by the name.
     */
    public static Kind kindOf(String variableName, String declared) {
        if (declared != null && !declared.isBlank()) {
            String d = declared.trim().toLowerCase(Locale.ROOT);
            switch (d) {
                case "text", "string" -> { return Kind.TEXT; }
                case "amount", "currency", "money" -> { return Kind.AMOUNT; }
                case "date" -> { return Kind.DATE; }
                case "identifier", "id", "number_id" -> { return Kind.IDENTIFIER; }
                case "any" -> { return Kind.ANY; }
                default -> { /* unknown declaration: fall back to the name */ }
            }
        }
        if (variableName == null) return Kind.ANY;
        String n = variableName.trim().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String last = dot >= 0 ? n.substring(dot + 1) : n;
        if (endsWithPart(last, TEXT_ENDINGS)) return Kind.TEXT;
        if (endsWithPart(last, AMOUNT_ENDINGS)) return Kind.AMOUNT;
        if (endsWithPart(last, List.of("date"))) return Kind.DATE;
        if (endsWithPart(last, IDENTIFIER_ENDINGS)) return Kind.IDENTIFIER;
        return Kind.ANY;
    }

    private static boolean endsWithPart(String name, List<String> endings) {
        for (String e : endings) if (name.equals(e) || name.endsWith("_" + e)) return true;
        return false;
    }

    /**
     * True when {@code value} can stand where a {@code kind} is expected: TEXT is not a bare number or amount; AMOUNT
     * and DATE carry a digit (words alone, e.g. "has been paid", are not an amount or a date); IDENTIFIER carries a digit or letter and is not a sentence; ANY
     * always fits. A blank value never fits (it is missing).
     */
    public static boolean fits(Kind kind, Object value) {
        if (value == null) return false;
        if (value instanceof Number) return kind != Kind.TEXT && kind != Kind.DATE;
        String v = value.toString().trim();
        if (v.isEmpty()) return false;
        return switch (kind) {
            case TEXT -> !BARE_AMOUNT.matcher(v).matches();
            case AMOUNT -> v.matches("(?s).*\\d.*");   // "Rs 1,00,000 (CGST 50,000 + SGST 50,000)" still fits
            case DATE -> DATE_LIKE.matcher(v).find() || v.matches("(?s).*\\d.*");
            case IDENTIFIER -> v.matches("(?s).*[A-Za-z0-9].*") && v.split("\\s+").length <= 8;
            case ANY -> true;
        };
    }

    /** True when {@code value} fits the kind {@link #kindOf(String) implied by} {@code variableName}. */
    public static boolean fits(String variableName, Object value) {
        return fits(kindOf(variableName), value);
    }

    /** The run-summary line for a rejected value: "variable rejected: issue.late_fee_status expected text". */
    public static String rejectionNote(String variableName) {
        return "variable rejected: " + variableName + " expected " + kindOf(variableName).label();
    }
}
