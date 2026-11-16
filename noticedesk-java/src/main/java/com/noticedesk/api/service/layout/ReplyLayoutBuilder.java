package com.noticedesk.api.service.layout;

import com.noticedesk.api.service.layout.LayoutMeta.IssueMeta;
import com.noticedesk.api.service.layout.LayoutMeta.ParaMeta;
import com.noticedesk.api.service.layout.ReplyLayout.Block;
import com.noticedesk.api.service.layout.ReplyLayout.PageBreak;
import com.noticedesk.api.service.layout.ReplyLayout.Para;
import com.noticedesk.api.service.layout.ReplyLayout.Style;
import com.noticedesk.api.service.layout.ReplyLayout.Table;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.noticedesk.api.service.layout.ReplyLayout.hl;
import static com.noticedesk.api.service.layout.ReplyLayout.humanise;

/**
 * Lays out the 15 draft sections in the partner-approved filing order (16-11-2026):
 * <ol>
 *   <li>the REPLY: letterhead, date, To (officer and office), From (client, address, GSTIN), bold subject, salutation,
 *       one opening paragraph; Part 1 background; Part 2 summary table (No. | Ground | Amount | Company's position);
 *       Part 3 issue-wise reply ("Issue N: title (Rs amount)", sub-headings Facts / Legal submissions, paragraphs 3.1,
 *       3.2 ...); Part 4 procedural objections; Part 5 para-wise reply as a table pointing to "Issue N"; cross-examination
 *       when requested; annexures as a table; the prayer as (a), (b), (c); the signature block;</li>
 *   <li>a page break and the INTERNAL REVIEW SHEET - NOT FOR FILING: A. items to fill before filing, B. points for the
 *       partner, C. filing checklist.</li>
 * </ol>
 * Code-check tables, flag names, card / template ids and costs never reach the reply; the developer check table is
 * not in the review sheet either (it stays in the run summary). Every placeholder is a short highlighted phrase.
 */
public final class ReplyLayoutBuilder {

    private ReplyLayoutBuilder() {}

    /** One stored draft section. */
    public record Section(int num, String title, String html) {}

    private static final Pattern ISSUE_H3 = Pattern.compile("(?is)<h3>\\s*(?:Legal Submissions for\\s+)?Issue\\s*#\\s*(\\d+)[^<]*</h3>");
    private static final Pattern OLD_NUMBER = Pattern.compile("^\\s*(\\d{1,2}\\.\\d{1,3})\\s+");
    private static final Pattern PARA_REF = Pattern.compile("(?i)\\b(paras?|paragraphs?)\\s+(\\d{1,2}\\.\\d{1,3})(?:\\s*(,|and|to)\\s*(\\d{1,2}\\.\\d{1,3}))?");
    private static final Pattern SECTION_REF = Pattern.compile("(?i)\\bSections?\\s+0([4-7])(?:\\s+and\\s+0([4-7]))?\\b");
    private static final Pattern LEGAL_FILLER = Pattern.compile("(?i)^the legal submissions on this issue are set out with the grounds in section 04\\.?$");
    private static final Pattern EXEC_HEAD = Pattern.compile("(?i)^\\s*Issue\\s*#\\s*(\\d+)");
    private static final Pattern PARA_ITEM = Pattern.compile("(?is)^\\s*Para\\s+(\\d+)\\s*:\\s*(.*)$");

    public static ReplyLayout build(List<Section> sections) {
        Map<Integer, String> html = new HashMap<>();
        for (Section s : sections != null ? sections : List.<Section>of()) html.put(s.num(), s.html() != null ? s.html() : "");
        LayoutMeta meta = LayoutMeta.fromHtml(html.get(13));
        if (meta == null) meta = fallbackMeta(html);

        Map<Integer, List<String>> facts = issueParagraphs(html.get(4));
        Map<Integer, List<String>> legal = issueParagraphs(html.get(6));
        Map<Integer, List<String>> procedural = issueParagraphs(html.get(7));
        Map<Integer, String> positions = positions(html.get(1));

        List<Block> reply = new ArrayList<>();
        letter(reply, meta);

        Map<String, String> renumber = new HashMap<>();
        int part = 0;

        // Part: background (the code-built facts of Section 03)
        List<String> background = paragraphs(html.get(3));
        if (!background.isEmpty()) {
            part++;
            reply.add(Para.of(Style.PART, part + ". BACKGROUND"));
            numbered(reply, part, background, renumber);
        }

        List<IssueMeta> substantive = meta.issues().stream().filter(i -> !LayoutMeta.KIND_DEFECT.equals(i.kind())).toList();
        List<IssueMeta> defects = meta.issues().stream().filter(i -> LayoutMeta.KIND_DEFECT.equals(i.kind())).toList();

        // Part: summary of the reply
        part++;
        reply.add(Para.of(Style.PART, part + ". SUMMARY OF THE REPLY"));
        List<List<String>> rows = new ArrayList<>();
        for (IssueMeta i : concat(substantive, defects)) {
            String position = i.position() != null && !i.position().isBlank() ? i.position() : positions.get(i.no());
            rows.add(List.of(String.valueOf(i.no()), humanise(title(i)), i.amount() != null ? i.amount() : "-",
                    summaryPosition(position, i)));
        }
        if (meta.totalDemand() != null && !meta.totalDemand().isBlank()) {
            rows.add(List.of("", "Total tax demand", humanise(meta.totalDemand()), ""));
        }
        // late fee / interest / penalty the notice states, each on its own row (never added to the tax total)
        for (LayoutMeta.DemandRow d : meta.demandRows()) rows.add(List.of("", humanise(d.label()), humanise(d.amount()), ""));
        reply.add(new Table(List.of("No.", "Ground", "Amount", "Company's position"), rows, Set.of(2)));

        // Part: issue-wise reply
        int issuePart = 0;
        if (!substantive.isEmpty()) {
            part++;
            issuePart = part;
            reply.add(Para.of(Style.PART, part + ". ISSUE-WISE REPLY"));
            int[] n = {1};
            for (IssueMeta i : substantive) {
                reply.add(Para.of(Style.HEADING, issueHeading(i)));
                List<String> f = clean(facts.get(i.no()));
                List<String> l = clean(legal.get(i.no()));
                // a skipped / failed issue prints the same partner line in both sections
                l.removeIf(x -> f.stream().anyMatch(y -> unnumbered(y).equals(unnumbered(x))));
                List<String> both = new ArrayList<>(f);
                both.addAll(l);
                if (both.isEmpty()) {
                    reply.add(new Para(Style.BODY, part + "." + n[0]++, partnerToDraft(i)));
                    continue;
                }
                if (both.stream().allMatch(x -> unnumbered(x).matches("(?s)\\[\\[\\s*(?:PARTNER|PENDING_AI)\\b.*\\]\\]"))) {
                    numbered(reply, part, n, List.of(both.get(0)), renumber);   // a skipped / failed draft: one partner line
                    continue;
                }
                if (!f.isEmpty()) {
                    reply.add(Para.of(Style.SUBHEADING, "Facts"));
                    numbered(reply, part, n, f, renumber);
                }
                if (!l.isEmpty()) {
                    reply.add(Para.of(Style.SUBHEADING, "Legal submissions"));
                    numbered(reply, part, n, l, renumber);
                }
            }
        }

        // Part: procedural objections
        part++;
        int procPart = part;
        reply.add(Para.of(Style.PART, part + ". PROCEDURAL OBJECTIONS"));
        if (defects.isEmpty()) {
            List<String> general = clean(paragraphs(html.get(7)));
            numbered(reply, part, general.isEmpty() ? List.of(hl("procedural objections, if any, after checklist review")) : general, renumber);
        } else {
            int[] n = {1};
            for (IssueMeta i : defects) {
                reply.add(Para.of(Style.HEADING, issueHeading(i)));
                List<String> p = clean(procedural.get(i.no()));
                if (p.isEmpty()) p = List.of(partnerToDraft(i));
                numbered(reply, part, n, p, renumber);
            }
        }

        // Part: para-wise reply
        part++;
        int paraPart = part;
        reply.add(Para.of(Style.PART, part + ". PARA-WISE REPLY"));
        Set<Integer> existing = new HashSet<>();
        meta.issues().forEach(i -> existing.add(i.no()));
        List<List<String>> paraRows = new ArrayList<>();
        for (ParaMeta p : meta.paras()) paraRows.add(List.of("Para " + p.no(), paraReply(p, existing)));
        if (paraRows.isEmpty()) paraRows.add(List.of("-", hl("paragraph-wise reply")));
        reply.add(new Table(List.of("Para of the notice", "Reply"), paraRows, Set.of()));

        // Part: cross-examination (only when requested)
        List<String> cross = clean(paragraphs(html.get(8)));
        if (!cross.isEmpty() && !(cross.size() == 1 && cross.get(0).matches("(?i)not applicable\\.?"))) {
            part++;
            reply.add(Para.of(Style.PART, part + ". REQUEST FOR CROSS-EXAMINATION"));
            numbered(reply, part, cross, renumber);
        }

        // Part: annexures
        part++;
        reply.add(Para.of(Style.PART, part + ". ANNEXURES"));
        reply.add(new Table(List.of("Annexure", "Document"), annexures(html.get(11), html.get(10)), Set.of()));

        // Part: prayer
        part++;
        reply.add(Para.of(Style.PART, part + ". PRAYER"));
        List<String> prayer = prayerItems(html.get(12));
        char letter = 'a';
        for (String item : prayer) reply.add(new Para(Style.ITEM, "(" + letter++ + ")", item));

        signature(reply, meta);

        // cross-references to the old section numbers, now that the new paragraph numbers are known
        Map<String, String> partNames = new HashMap<>();
        partNames.put("4", issuePart > 0 ? "Part " + issuePart : "Part " + procPart);
        partNames.put("6", issuePart > 0 ? "Part " + issuePart : "Part " + procPart);
        partNames.put("7", "Part " + procPart);
        partNames.put("5", "Part " + paraPart);
        reply = rewriteRefs(reply, renumber, partNames);

        return new ReplyLayout(reply, review(meta, html, reply));
    }

    // ------------------------------------------------------------------ the letter

    private static void letter(List<Block> out, LayoutMeta m) {
        out.add(Para.of(Style.LETTER, hl("Firm letterhead")));
        out.add(Para.of(Style.LETTER, "Date: " + hl("date of filing")));
        out.add(Para.of(Style.LETTER, "To,"));
        out.add(Para.of(Style.LETTER, orHl(m.officer(), "officer designation")));
        if (m.officeLines().isEmpty()) out.add(Para.of(Style.LETTER, hl("office address")));
        for (String l : m.officeLines()) out.add(Para.of(Style.LETTER, humanise(l)));
        out.add(Para.of(Style.LETTER, "From,"));
        out.add(Para.of(Style.LETTER, orHl(m.clientName(), "client name")));
        if (m.clientAddress().isEmpty()) out.add(Para.of(Style.LETTER, hl("client address")));
        for (String l : m.clientAddress()) out.add(Para.of(Style.LETTER, humanise(l)));
        out.add(Para.of(Style.LETTER, "GSTIN: " + orHl(m.gstin(), "GSTIN")));
        // the notice type without its provision ("Show Cause Notice"); "Notice" when the type is unknown or a marker
        String type = m.noticeType() != null && !m.noticeType().isBlank() && !m.noticeType().contains("[[") ? m.noticeType()
                : m.docLabel() != null && !m.docLabel().isBlank() && !m.docLabel().contains("[[") ? m.docLabel() : "Notice";
        out.add(Para.of(Style.SUBJECT, "Subject: Reply to the " + stripUnder(type) + " " + orHl(m.noticeRef(), "notice reference")
                + " dated " + orHl(m.noticeDate(), "notice date") + " under " + orHl(m.noticeSection(), "section")
                + " of the " + nonBlank(m.lawName(), "CGST Act, 2017") + " for " + orHl(m.period(), "period")));
        out.add(Para.of(Style.LETTER, "Respected Sir / Madam,"));
        out.add(Para.of(Style.BODY, "With reference to the above notice, " + orHl(m.clientName(), "client name")
                + " (\"the Company\") submits its reply as set out below. Every allegation in the notice is denied except what"
                + " is expressly admitted in this reply, and this reply is submitted without prejudice to the Company's other"
                + " rights and contentions. The Company requests that the reply be taken on record and that it be given an"
                + " opportunity of personal hearing before any order is passed."));
    }

    /** "Show Cause Notice under Section 74(1)" -> "Show Cause Notice" (the subject names the section itself). */
    private static String stripUnder(String type) {
        return type.replaceFirst("(?i)\\s+(?:issued\\s+)?under\\s+.*$", "").trim();
    }

    private static void signature(List<Block> out, LayoutMeta m) {
        out.add(Para.of(Style.SIGNATURE, "For " + orHl(m.clientName(), "client name")));
        out.add(Para.of(Style.SIGNATURE, hl("signature of the authorised signatory")));
        out.add(Para.of(Style.SIGNATURE, "Name: " + hl("name of the authorised signatory")));
        out.add(Para.of(Style.SIGNATURE, "Designation: " + hl("designation")));
        out.add(Para.of(Style.SIGNATURE, "Place: " + hl("place")));
        out.add(Para.of(Style.SIGNATURE, "Date: " + hl("date of filing")));
    }

    // ------------------------------------------------------------------ issues

    static String title(IssueMeta i) {
        return i.title() != null && !i.title().isBlank() ? i.title().trim() : "Issue " + i.no();
    }

    /** "Issue N: plain title (Rs amount)"; the amount left out when there is none. */
    static String issueHeading(IssueMeta i) {
        String h = "Issue " + i.no() + ": " + humanise(title(i));
        if (i.amount() != null && !i.amount().isBlank()) h += " (" + humanise(i.amount()) + ")";
        if (LayoutMeta.KIND_CONSEQUENTIAL.equals(i.kind()) && i.parentNo() != null) h += ", consequential to Issue " + i.parentNo();
        return h;
    }

    private static String partnerToDraft(IssueMeta i) {
        String note = i.writerNote();
        if (note != null && note.toLowerCase(Locale.ROOT).contains("skipped")) {
            String reason = note.replaceFirst("(?i)^.*skipped\\s*[,(]?\\s*", "").replaceAll("[()]", "").trim();
            return hl("Partner to draft: the AI draft for this issue was skipped (" + (reason.isEmpty() ? "no reason given" : reason) + ")");
        }
        if (note != null && note.toLowerCase(Locale.ROOT).contains("failed")) {
            return hl("Partner to draft: the AI draft for this issue failed");
        }
        return hl("Partner to draft the reply to this issue");
    }

    private static String summaryPosition(String position, IssueMeta i) {
        if (position == null || position.isBlank()) return hl("position to be stated");
        String p = humanise(position);
        if (p.contains(String.valueOf(ReplyLayout.HL_START)) && ReplyLayout.plain(p).trim().startsWith("[")) {
            return hl("Partner to draft");
        }
        // "Grounds of defence are set out at para 4.3." -> a pointer to the issue heading
        if (ReplyLayout.plain(p).matches("(?is)grounds of defence are set out at para.*")) return "Not sustainable; see the reply to Issue " + i.no() + ".";
        return p;
    }

    private static String paraReply(ParaMeta p, Set<Integer> existing) {
        String stance = p.stance() != null ? p.stance() : "";
        switch (stance) {
            case LayoutMeta.ADMITTED -> {
                return "Admitted.";
            }
            case LayoutMeta.NOTED -> {
                return "Noted.";
            }
            case LayoutMeta.DENIED -> {
                List<Integer> refs = p.issues().stream().filter(existing::contains).distinct().sorted().toList();
                if (refs.isEmpty()) return "Denied, for the reasons set out in this reply.";
                if (refs.size() == 1) return "Denied. See the reply to Issue " + refs.get(0) + ".";
                List<String> s = refs.stream().map(String::valueOf).toList();
                return "Denied. See the replies to Issues " + String.join(", ", s.subList(0, s.size() - 1)) + " and " + s.get(s.size() - 1) + ".";
            }
            default -> {
                return hl("reply to Para " + p.no());
            }
        }
    }

    // ------------------------------------------------------------------ HTML to paragraphs

    /** Section 04 / 06 / 07 split by the issue headings: issue number -> its paragraphs' HTML text. */
    static Map<Integer, List<String>> issueParagraphs(String html) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        if (html == null) return out;
        Matcher m = ISSUE_H3.matcher(html);
        List<int[]> heads = new ArrayList<>();
        List<Integer> nos = new ArrayList<>();
        while (m.find()) {
            heads.add(new int[]{m.start(), m.end()});
            nos.add(Integer.parseInt(m.group(1)));
        }
        for (int k = 0; k < heads.size(); k++) {
            int end = k + 1 < heads.size() ? heads.get(k + 1)[0] : html.length();
            out.computeIfAbsent(nos.get(k), x -> new ArrayList<>()).addAll(paragraphs(html.substring(heads.get(k)[1], end)));
        }
        return out;
    }

    /** The text of each paragraph / list item of {@code html} (comments and the metadata left out). */
    static List<String> paragraphs(String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isBlank()) return out;
        org.jsoup.nodes.Document d = Jsoup.parseBodyFragment(LayoutMeta.strip(html));
        collect(d.body(), out);
        return out;
    }

    private static void collect(Element el, List<String> out) {
        StringBuilder loose = new StringBuilder();
        for (Node n : el.childNodes()) {
            if (n instanceof TextNode t) {
                loose.append(t.text());
            } else if (n instanceof Element e) {
                String tag = e.tagName();
                if (tag.matches("p|li|h[1-6]|td|th|tr")) {
                    flush(loose, out);
                    if (tag.equals("li") && !e.select("ul, ol").isEmpty()) {
                        Element own = e.clone();
                        own.select("ul, ol").remove();
                        add(out, own.text());
                        for (Element sub : e.select("> ul, > ol")) collect(sub, out);
                    } else {
                        add(out, e.text());
                    }
                } else if (tag.matches("ul|ol|div|table|tbody|thead|section|blockquote")) {
                    flush(loose, out);
                    collect(e, out);
                } else if (tag.equals("br")) {
                    flush(loose, out);
                } else {
                    loose.append(e.text());
                }
            }
        }
        flush(loose, out);
    }

    private static void flush(StringBuilder sb, List<String> out) {
        add(out, sb.toString());
        sb.setLength(0);
    }

    private static void add(List<String> out, String s) {
        String t = s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        if (!t.isEmpty()) out.add(t);
    }

    /** Paragraphs without the legal-submissions filler line; null-safe; a copy. */
    private static List<String> clean(List<String> paras) {
        List<String> out = new ArrayList<>();
        if (paras == null) return out;
        for (String p : paras) {
            String t = OLD_NUMBER.matcher(p).replaceFirst("");
            if (LEGAL_FILLER.matcher(t).matches()) continue;
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    private static String unnumbered(String p) {
        return OLD_NUMBER.matcher(p).replaceFirst("").trim();
    }

    private static void numbered(List<Block> out, int part, List<String> paras, Map<String, String> renumber) {
        numbered(out, part, new int[]{1}, paras, renumber);
    }

    /** Each paragraph numbered "{part}.{n}"; an old number at its start ("4.3 ...") is dropped and mapped to the new one. */
    private static void numbered(List<Block> out, int part, int[] n, List<String> paras, Map<String, String> renumber) {
        for (String p : paras) {
            String number = part + "." + n[0]++;
            Matcher m = OLD_NUMBER.matcher(p);
            String text = p;
            if (m.find()) {
                renumber.putIfAbsent(m.group(1), number);
                text = p.substring(m.end());
            }
            out.add(new Para(Style.BODY, number, humanise(text)));
        }
    }

    /** "para 4.3" -> the new number; "Section 04" / "Sections 04 and 06" -> the Part that now holds that text. */
    private static List<Block> rewriteRefs(List<Block> blocks, Map<String, String> renumber, Map<String, String> partNames) {
        List<Block> out = new ArrayList<>();
        for (Block b : blocks) {
            if (b instanceof Para p && p.text() != null) {
                out.add(new Para(p.style(), p.number(), rewrite(p.text(), renumber, partNames)));
            } else if (b instanceof Table t) {
                List<List<String>> rows = new ArrayList<>();
                for (List<String> r : t.rows()) rows.add(r.stream().map(c -> rewrite(c, renumber, partNames)).toList());
                out.add(new Table(t.header(), rows, t.rightAligned()));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private static String rewrite(String text, Map<String, String> renumber, Map<String, String> partNames) {
        if (text == null) return null;
        Matcher m = PARA_REF.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String a = renumber.get(m.group(2));
            String rep = m.group(1) + " " + (a != null ? a : m.group(2));
            if (m.group(4) != null) {
                String b = renumber.get(m.group(4));
                String bn = b != null ? b : m.group(4);
                rep = bn.equals(a != null ? a : m.group(2)) ? rep : rep + " " + m.group(3) + " " + bn;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        Matcher s = SECTION_REF.matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        while (s.find()) {
            String first = partNames.getOrDefault(s.group(1), "this reply");
            String second = s.group(2) != null ? partNames.getOrDefault(s.group(2), "this reply") : null;
            String rep = second == null || second.equals(first) ? first : first + " and " + second;
            s.appendReplacement(sb2, Matcher.quoteReplacement(rep));
        }
        s.appendTail(sb2);
        return sb2.toString();
    }

    // ------------------------------------------------------------------ positions, annexures, prayer

    /** Issue number -> its one-line position from the executive summary list of Section 01. */
    static Map<Integer, String> positions(String html) {
        Map<Integer, String> out = new HashMap<>();
        if (html == null) return out;
        for (Element li : Jsoup.parseBodyFragment(html).select("li")) {
            Matcher m = EXEC_HEAD.matcher(li.text());
            if (!m.find()) continue;
            String rest = li.text().substring(m.end()).trim();
            if (rest.startsWith("(")) {   // "(Section 17(5)(d); amount involved: Rs 28,64,200)": skip the balanced parentheses
                int depth = 0;
                int k = 0;
                for (; k < rest.length(); k++) {
                    if (rest.charAt(k) == '(') depth++;
                    else if (rest.charAt(k) == ')' && --depth == 0) break;
                }
                rest = k < rest.length() ? rest.substring(k + 1).trim() : "";
            }
            if (rest.startsWith(":")) out.put(Integer.parseInt(m.group(1)), rest.substring(1).trim());
        }
        return out;
    }

    private static List<List<String>> annexures(String sec11, String sec10) {
        List<List<String>> rows = new ArrayList<>();
        if (sec11 != null) {
            for (Element tr : Jsoup.parseBodyFragment(sec11).select("tr")) {
                var tds = tr.select("td");
                if (tds.size() >= 2) rows.add(List.of(tds.get(0).text().replaceFirst("(?i)^Annexure\\s+", ""), humanise(tds.get(1).text())));
            }
        }
        if (rows.isEmpty() && sec10 != null) {
            int i = 1;
            for (Element li : Jsoup.parseBodyFragment(sec10).select("li")) rows.add(List.of("A-" + i++, humanise(li.text())));
        }
        if (rows.isEmpty()) rows.add(List.of("-", hl("list the documents to be enclosed")));
        return rows;
    }

    /** The prayer of Section 12 as separate requests: its list items, else its clauses split at ";" and sentences. */
    static List<String> prayerItems(String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isBlank()) {
            out.add(hl("prayer"));
            return out;
        }
        var doc = Jsoup.parseBodyFragment(LayoutMeta.strip(html));
        var lis = doc.select("li");
        if (!lis.isEmpty()) {
            for (Element li : lis) add(out, humanise(li.text()));
            return out;
        }
        String text = doc.text().replaceFirst("(?i)^\\s*(\\[[^\\]]*\\]\\s*)*(DRAFT - partner to review\\.\\s*)?PRAYER\\s*:\\s*", "");
        String prefix = doc.text().startsWith("[DEV") ? "[DEV - not CA reviewed] " : "";
        List<String> parts = new ArrayList<>();
        for (String sentence : text.split("(?<=\\.)\\s+(?=It is |Further|The Noticee|It may )")) {
            for (String clause : sentence.split(";\\s*(?:and\\s+)?")) add(parts, clause);
        }
        for (int k = 0; k < parts.size(); k++) {
            String p = parts.get(k);
            p = Character.toUpperCase(p.charAt(0)) + p.substring(1);
            if (!p.matches("(?s).*[.;:!?]$")) p += k == parts.size() - 1 ? "." : ";";
            out.add(humanise((k == 0 ? prefix : "") + p));
        }
        if (out.isEmpty()) out.add(hl("prayer"));
        return out;
    }

    // ------------------------------------------------------------------ the review sheet

    private static List<Block> review(LayoutMeta meta, Map<Integer, String> html, List<Block> reply) {
        List<Block> out = new ArrayList<>();
        out.add(new PageBreak());
        out.add(Para.of(Style.TITLE, ReplyLayout.REVIEW_TITLE));
        out.add(Para.of(Style.LETTER, "Notice " + orHl(meta.noticeRef(), "notice reference") + " dated " + orHl(meta.noticeDate(), "notice date")
                + "; client " + orHl(meta.clientName(), "client name") + "."));

        // A. items to fill before filing: every placeholder of the reply and where it is
        out.add(Para.of(Style.PART, "A. ITEMS TO FILL BEFORE FILING"));
        Map<String, LinkedHashSet<String>> items = new LinkedHashMap<>();
        String where = "Letter";
        for (Block b : reply) {
            if (b instanceof Para p) {
                if (p.style() == Style.PART) where = partLabel(p.text());
                else if (p.style() == Style.HEADING) where = p.text().replaceFirst(":.*$", "");
                else if (p.style() == Style.SIGNATURE) where = "Signature block";
                collectPlaceholders(p.text(), where + (p.number() != null && p.style() == Style.BODY ? ", para " + p.number() : ""), items);
            } else if (b instanceof Table t) {
                for (List<String> r : t.rows()) for (String c : r) collectPlaceholders(c, where, items);
            }
        }
        List<List<String>> rows = new ArrayList<>();
        for (var e : items.entrySet()) rows.add(List.of(e.getKey(), String.join("; ", e.getValue())));
        if (rows.isEmpty()) rows.add(List.of("None", "-"));
        out.add(new Table(List.of("Item", "Where needed"), rows, Set.of()));

        // B. points for the partner, read from the internal note (Section 13) and the client summary (Section 14)
        out.add(Para.of(Style.PART, "B. POINTS FOR THE PARTNER"));
        Map<String, List<String>> note = noteLists(html.get(13));
        List<String> notes = note.getOrDefault("stage 3 notes", List.of());
        List<String> codeChecks = notes.stream().filter(n -> n.matches("(?is).*(code check|code defect|disput|matcher).*")).toList();
        List<String> otherNotes = notes.stream().filter(n -> !codeChecks.contains(n)).toList();
        List<String> verify = new ArrayList<>();
        for (Block b : reply) {
            String t = b instanceof Para p ? p.text() : null;
            if (t != null) {
                Matcher m = Pattern.compile("\\[VERIFY[^\\]]*\\]").matcher(ReplyLayout.plain(t));
                while (m.find()) if (!verify.contains(m.group())) verify.add(m.group());
            }
        }
        notes.stream().filter(n -> n.matches("(?is).*\\b(citation|NOT_FOUND|unverified)\\b.*")).forEach(n -> { if (!verify.contains(n)) verify.add(n); });
        reviewList(out, "Notes on the templates and the drafting", otherNotes);
        reviewList(out, "Code checks that disagree with the matcher or need a decision", codeChecks);
        reviewList(out, "Citations marked VERIFY", verify);
        reviewList(out, "Facts to confirm with the client", clientFacts(html.get(13), note));
        reviewList(out, "Procedural objections the partner may add", note.getOrDefault("procedural objection options (partner to select)", List.of()));
        reviewList(out, "Documents to collect from the client", documentsFromClient(html.get(14)));

        // C. filing checklist
        out.add(Para.of(Style.PART, "C. FILING CHECKLIST"));
        List<String> checklist = checklistItems(html.get(15));
        int k = 1;
        for (String c : checklist) out.add(new Para(Style.ITEM, k++ + ".", humanise(c)));
        out.add(new Para(Style.ITEM, k + ".", "Every highlighted item in the reply filled in and this review sheet removed before filing."));
        return out;
    }

    /** "3. ISSUE-WISE REPLY" -> "Part 3 (Issue-wise reply)". */
    private static String partLabel(String heading) {
        Matcher m = Pattern.compile("^(\\d+)\\.\\s*(.*)$").matcher(heading);
        if (!m.matches()) return heading;
        String name = m.group(2).toLowerCase(Locale.ROOT);
        return "Part " + m.group(1) + " (" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + ")";
    }

    private static void collectPlaceholders(String text, String where, Map<String, LinkedHashSet<String>> items) {
        if (text == null) return;
        int i = text.indexOf(ReplyLayout.HL_START);
        while (i >= 0) {
            int j = text.indexOf(ReplyLayout.HL_END, i);
            if (j < 0) break;
            String phrase = text.substring(i + 1, j).replaceAll("^\\[|\\]$", "");
            items.computeIfAbsent(phrase, x -> new LinkedHashSet<>()).add(where);
            i = text.indexOf(ReplyLayout.HL_START, j);
        }
    }

    private static void reviewList(List<Block> out, String heading, List<String> items) {
        out.add(Para.of(Style.SUBHEADING, heading));
        if (items == null || items.isEmpty()) {
            out.add(Para.of(Style.ITEM, "None."));
            return;
        }
        for (String s : items) out.add(new Para(Style.ITEM, "-", humanise(s)));
    }

    /** The lists of the internal note by their bold label ("stage 3 notes" -> items); the check table is not read. */
    static Map<String, List<String>> noteLists(String html) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (html == null) return out;
        var body = Jsoup.parseBodyFragment(LayoutMeta.strip(html)).body();
        String label = null;
        for (Element e : body.children()) {
            if (e.tagName().equals("p") && !e.select("strong").isEmpty()) {
                label = e.select("strong").first().text().replaceAll(":\\s*$", "").trim().toLowerCase(Locale.ROOT);
            } else if (label != null && (e.tagName().equals("ul") || e.tagName().equals("ol"))) {
                List<String> items = new ArrayList<>();
                for (Element li : e.select("> li")) add(items, li.text());
                out.put(label, items);
                label = null;
            }
        }
        return out;
    }

    private static List<String> clientFacts(String html, Map<String, List<String>> note) {
        List<String> out = new ArrayList<>(note.getOrDefault("facts to confirm with the client", List.of()));
        // per issue, from the matched-issues list: "Issue #n: ... Confirm with client: a; b"
        for (String li : note.getOrDefault("matched issues & templates", List.of())) {
            Matcher m = Pattern.compile("(?is)^Issue\\s*#\\s*(\\d+).*?Confirm with client:\\s*(.+)$").matcher(li);
            if (m.find()) {
                String s = "Issue " + m.group(1) + ": " + m.group(2).trim();
                if (!out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    private static List<String> documentsFromClient(String html) {
        List<String> out = new ArrayList<>();
        if (html == null) return out;
        for (Element li : Jsoup.parseBodyFragment(html).select("li")) add(out, li.text());
        return out;
    }

    static List<String> checklistItems(String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isBlank()) return out;
        var doc = Jsoup.parseBodyFragment(html);
        if (!doc.select("li").isEmpty()) {
            for (Element li : doc.select("li")) add(out, li.text());
            return out;
        }
        String t = doc.text().replaceFirst("(?i)^.*?FILING CHECKLIST\\s*:\\s*", "");
        for (String s : t.split("\\s*(?:^|\\s)\\d{1,2}\\.\\s+")) add(out, s.replaceAll("[.;]\\s*$", ""));
        return out;
    }

    // ------------------------------------------------------------------ fallback and helpers

    /** Metadata read from the sections themselves, for a draft stored before the layout comment existed. */
    static LayoutMeta fallbackMeta(Map<Integer, String> html) {
        Set<Integer> seen = new LinkedHashSet<>();
        seen.addAll(issueParagraphs(html.get(4)).keySet());
        seen.addAll(issueParagraphs(html.get(6)).keySet());
        Set<Integer> defects = issueParagraphs(html.get(7)).keySet();
        List<IssueMeta> issues = new ArrayList<>();
        for (int n : seen) if (!defects.contains(n)) issues.add(new IssueMeta(n, null, null, LayoutMeta.KIND_DEMAND, null, null, null));
        for (int n : defects) issues.add(new IssueMeta(n, null, null, LayoutMeta.KIND_DEFECT, null, null, null));
        List<ParaMeta> paras = new ArrayList<>();
        for (String li : paragraphs(html.get(5))) {
            Matcher m = PARA_ITEM.matcher(li);
            if (!m.matches()) continue;
            String r = m.group(2).trim().toLowerCase(Locale.ROOT);
            String stance = r.startsWith("admitted") ? LayoutMeta.ADMITTED
                    : r.startsWith("noted") || r.startsWith("matter of record") ? LayoutMeta.NOTED
                    : r.startsWith("[[partner") ? LayoutMeta.PARTNER : LayoutMeta.DENIED;
            List<Integer> refs = new ArrayList<>();
            Matcher im = Pattern.compile("#(\\d+)").matcher(m.group(2));
            while (im.find()) refs.add(Integer.parseInt(im.group(1)));
            paras.add(new ParaMeta(Integer.parseInt(m.group(1)), stance, refs));
        }
        return new LayoutMeta(null, null, null, null, null, null, null, null, List.of(), null, List.of(), null, null, issues, paras, List.of());
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static String orHl(String v, String phrase) {
        return v == null || v.isBlank() || v.contains("[[MISSING") ? hl(phrase) : humanise(v);
    }

    private static String nonBlank(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v;
    }
}
