package com.noticedesk.api.service.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The addressee and the client's address for the reply letter, read from the notice text: the issuing officer from the
 * "OFFICE OF THE ..." heading (else the designation in the signature block), the office lines under it, and the
 * address lines under the noticee's name in the "To," block. Null / empty when the text does not show them (the
 * layout then prints a highlighted placeholder).
 */
public final class NoticeLetterHead {

    private NoticeLetterHead() {}

    public record Head(String officer, List<String> officeLines, List<String> clientAddress) {}

    private static final Pattern OFFICE_OF = Pattern.compile("(?im)^[ \\t]*(?:the\\s+)?office\\s+of\\s+the\\s+(.+?)[ \\t]*$");
    private static final Pattern DESIGNATION = Pattern.compile(
            "(?i)^(?:the\\s+)?((?:principal\\s+|additional\\s+|addl\\.?\\s+|joint\\s+|deputy\\s+|dy\\.?\\s+|assistant\\s+|asst\\.?\\s+|chief\\s+)*"
            + "(?:commissioner|superintendent|inspector|proper officer|state tax officer|assistant commissioner)\\b.*)$");
    private static final Pattern NOT_OFFICE = Pattern.compile(
            "(?i)^\\s*(?:DIN|RFN|C\\.?\\s*No|F\\.?\\s*No|Ref|Date|Dated|No\\.|Subject|Sub|To|SHOW CAUSE|ORDER|NOTICE|FORM)\\b");
    private static final Pattern TO_LINE = Pattern.compile("(?im)^[ \\t]*To[ \\t]*,?[ \\t]*$");
    private static final Pattern ADDRESS_END = Pattern.compile("(?i)^\\s*(?:GSTIN|PAN|Period|Sub|Subject|Ref|Dear|Sir|Madam)\\b|^\\s*\\d+\\.\\s");
    private static final Set<String> KEEP_UPPER = Set.of("GST", "CGST", "SGST", "IGST", "UTGST", "CBIC", "GSTIN", "PAN", "DGGI", "II", "III", "IV");

    public static Head read(String text) {
        if (text == null || text.isBlank()) return new Head(null, List.of(), List.of());
        String[] lines = text.split("\\R");
        String officer = null;
        List<String> office = new ArrayList<>();
        Matcher m = OFFICE_OF.matcher(text);
        if (m.find()) {
            officer = "The " + titleCase(m.group(1).replaceAll("[,.]+$", ""));
            int at = lineIndexAt(text, m.start());
            for (int i = at + 1; i < lines.length && office.size() < 3; i++) {
                String l = lines[i].trim();
                if (l.isEmpty() || NOT_OFFICE.matcher(l).find()) break;
                office.add(titleCase(l));
            }
        } else {
            // the designation in the last lines (signature block)
            for (int i = lines.length - 1; i >= Math.max(0, lines.length - 12); i--) {
                Matcher d = DESIGNATION.matcher(lines[i].trim());
                if (d.matches()) {
                    officer = "The " + titleCase(d.group(1).replaceAll("[,.]+$", ""));
                    for (int j = i + 1; j < lines.length && office.size() < 2; j++) {
                        if (!lines[j].isBlank()) office.add(titleCase(lines[j].trim()));
                    }
                    break;
                }
            }
        }
        List<String> address = new ArrayList<>();
        Matcher to = TO_LINE.matcher(text);
        if (to.find()) {
            int at = lineIndexAt(text, to.start());
            // the first non-blank line after "To," is the noticee's name; its address follows until GSTIN / Period
            int i = at + 1;
            while (i < lines.length && lines[i].isBlank()) i++;
            for (i = i + 1; i < lines.length && address.size() < 4; i++) {
                String l = lines[i].trim();
                if (l.isEmpty() || ADDRESS_END.matcher(l).find()) break;
                address.add(l);
            }
        }
        return new Head(officer, office, address);
    }

    private static int lineIndexAt(String text, int offset) {
        int n = 0;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    /** "BENGALURU SOUTH COMMISSIONERATE, GST BHAVAN" -> "Bengaluru South Commissionerate, GST Bhavan"; mixed case kept. */
    static String titleCase(String s) {
        if (s == null) return null;
        if (!s.equals(s.toUpperCase(Locale.ROOT))) return s;
        StringBuilder sb = new StringBuilder();
        Matcher w = Pattern.compile("[A-Za-z]+|[^A-Za-z]+").matcher(s);
        while (w.find()) {
            String t = w.group();
            if (!Character.isLetter(t.charAt(0)) || KEEP_UPPER.contains(t)) {
                sb.append(t);
            } else if (Set.of("OF", "THE", "AND", "FOR", "IN", "ON", "AT").contains(t) && sb.length() > 0) {
                sb.append(t.toLowerCase(Locale.ROOT));
            } else {
                sb.append(t.charAt(0)).append(t.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }
}
