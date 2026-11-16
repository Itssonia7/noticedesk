package com.noticedesk.api.service.layout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.layout.ReplyLayout.Block;
import com.noticedesk.api.service.layout.ReplyLayout.PageBreak;
import com.noticedesk.api.service.layout.ReplyLayout.Para;
import com.noticedesk.api.service.layout.ReplyLayout.Style;
import com.noticedesk.api.service.layout.ReplyLayout.Table;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.TemplateFillService.FilledBlock;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import com.noticedesk.api.testsupport.SyntheticS74Notice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (b44) The partner-approved filing layout (agreed 16-11-2026) built from a draft of the synthetic Section 74 notice:
 * the reply first (letter, summary table, issue-wise reply, procedural objections, para-wise table, annexures, prayer,
 * signature), then a page break and the internal review sheet. Synthetic templates and writer output only.
 */
class ReplyLayoutTest {

    private final ObjectMapper om = new ObjectMapper();

    /** The live run's shape: template issues 2 and 5 (5 has no grounds block), AI issues 1 and 4, 3 skipped, DIN defect 6. */
    ReplyLayout layout() {
        List<MatchedIssue> base = SyntheticS74Notice.issues();
        List<MatchedIssue> issues = new ArrayList<>();
        issues.add(with(base.get(0), "partial", List.of("CARD-A")));
        issues.add(with(base.get(1), "full", List.of("CARD-B")));
        issues.add(with(base.get(2), "none", List.of()));
        issues.add(with(base.get(3), "partial", List.of("CARD-D")));
        issues.add(with(base.get(4), "full", List.of("CARD-E")));
        issues.add(new MatchedIssue(6, "full", "defect", List.of("CARD-DIN"), List.of(), "code", List.of(),
                new LinkedHashMap<>(Map.of("period", "2019-20", "defect_check", "din")), List.of("header"), List.of(), List.of()));
        Map<String, List<Integer>> paraMap = new LinkedHashMap<>();
        paraMap.put("record", List.of(1));
        paraMap.put("issue", List.of(2, 3, 4, 5, 6));
        paraMap.put("demand", List.of(7, 8));
        paraMap.put("directions", List.of(9, 10));
        MatchingResult mr = new MatchingResult(SyntheticS74Notice.notice(SyntheticS74Notice.TOTAL), issues, paraMap, List.of(), List.of(),
                "hash", 1, 1, 0, 0);

        List<FilledTemplateResult> fts = List.of(
                none("CARD-A"),
                template("CARD-B", List.of(new FilledBlock("B1", 4, "<p>The MEIS scrips of face value Rs 11200000 were sold.</p>", List.of(), List.of(), List.of()),
                        new FilledBlock("B2", 6, "<p>The sale of duty credit scrips is not a supply attracting Rule 42; see para {{ref:B1}}.</p>", List.of(), List.of(), List.of()))),
                none(null),
                none("CARD-D"),
                template("CARD-E", List.of(new FilledBlock("E1", 6, "<p>Rule 86B does not apply where [[MISSING: rule86b_exception]] is met.</p>", List.of(), List.of(), List.of()))),
                none("CARD-DIN"));

        Map<Integer, AiIssueContent> ai = new HashMap<>();
        ai.put(1, new AiIssueContent(1, "partial", List.of(
                new AiFragment(4, null, "<p>The cement and steel were used for supplies of goods, not for construction on own account. The notice was received on [[MISSING: date_of_receipt]].</p>"),
                new AiFragment(6, null, "<p>Section 17(5)(d) applies only to construction of an immovable property on own account [[VERIFY: case name and citation]].</p>")),
                List.of(), "Credit is admissible: the goods were traded, not used for construction."));
        ai.put(3, new AiIssueContent(3, "none (AI draft skipped, cost cap)", List.of(
                new AiFragment(4, null, "<p>[[PARTNER: AI draft skipped — cost cap]]</p>"),
                new AiFragment(6, null, "<p>[[PARTNER: AI draft skipped — cost cap]]</p>")), List.of(), "[[PARTNER: AI draft skipped — cost cap]]"));
        ai.put(4, new AiIssueContent(4, "partial", List.of(
                new AiFragment(4, null, "<p>The supply to Hosur was billed with CGST and SGST in error; the tax was paid in full.</p>"),
                new AiFragment(6, null, "<p>Section 77 of the CGST Act provides for refund of the tax paid under the wrong head.</p>")),
                List.of(), "Tax paid under the wrong head is refundable under Section 77."));
        ai.put(6, new AiIssueContent(6, "new_issue", List.of(
                new AiFragment(4, null, "<p>The notice carries no valid DIN and is invalid under Circular 128/47/2019-GST.</p>")), List.of(), "No valid DIN."));

        ReplyAssemblyService svc = new ReplyAssemblyService(null, null, om);
        svc.registerInMemoryStageTemplate(new StageTemplate("scn_74", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                new TreeMap<>(Map.of("08", "<p>Cross-examination of the audit officers is requested.</p>",
                        "12", "<p>PRAYER: It is humbly prayed that the proceedings under {{notice.section}} be dropped in full; that Section 74 be held not invocable; and that no penalty be imposed. It is further requested that a personal hearing be granted.</p>",
                        "14", "<p>CLIENT SUMMARY: {{notice.type}}.</p>",
                        "15", "<p>FILING CHECKLIST: 1. Written Submissions 2. Form GST DRC-06 3. Authorisation Letter.</p>")), null, null, null));
        AssembledReplyResult r = svc.assembleReply(mr, fts, SyntheticS74Notice.input(), "scn_74", ai, null,
                List.of("Code defect checks: din=defect (central-tax document dated 29.11.2024: DIN field blank or a placeholder)."));
        List<ReplyLayoutBuilder.Section> sections = r.sections().stream()
                .map(s -> new ReplyLayoutBuilder.Section(s.num(), s.title(), s.bodyHtml())).toList();
        return ReplyLayoutBuilder.build(sections);
    }

    private static MatchedIssue with(MatchedIssue i, String status, List<String> cards) {
        return new MatchedIssue(i.issueNo(), status, "demand", cards, List.of(), "synthetic", List.of(), i.facts(), i.noticeParas(),
                List.of(), List.of());
    }

    private static FilledTemplateResult none(String card) {
        return new FilledTemplateResult("NONE", 0, card, null, List.of(), List.of(), List.of(), List.of("no_template_available"),
                List.of(), List.of(), false);
    }

    private static FilledTemplateResult template(String card, List<FilledBlock> blocks) {
        return new FilledTemplateResult("TPL-" + card, 1, card, "The demand on this ground is not sustainable.", blocks, List.of(), List.of(), List.of(),
                List.of("Copies of the scrips and the sale invoices"), List.of(), false);
    }

    private static String replyText(ReplyLayout l) {
        return ReplyLayoutText.renderReply(l);
    }

    @Test
    void sectionOrder_replyThenPageBreakThenReviewSheet() {
        ReplyLayout l = layout();
        String t = replyText(l);
        String[] order = {"[Firm letterhead]", "To,", "From,", "Subject: Reply to the Show Cause Notice", "Respected Sir / Madam,",
                "SUMMARY OF THE REPLY", "ISSUE-WISE REPLY", "PROCEDURAL OBJECTIONS", "PARA-WISE REPLY",
                "ANNEXURES", "PRAYER", "For M/s Vindhya Build Mart Private Limited"};
        int at = -1;
        for (String s : order) {
            int i = t.indexOf(s, at + 1);
            assertTrue(i > at, "'" + s + "' out of order or missing:\n" + t);
            at = i;
        }
        assertFalse(t.contains("CROSS-EXAMINATION"), "no cross-examination part when Section 08 is not applicable");
        assertFalse(t.contains("M/s Vindhya Build Mart Private Limited\nM/s Vindhya"), "the name is not repeated as an address line");
        assertTrue(l.review().get(0) instanceof PageBreak, "the review sheet starts after a page break");
        Para title = (Para) l.review().get(1);
        assertEquals(Style.TITLE, title.style());
        assertEquals("INTERNAL REVIEW SHEET - NOT FOR FILING", title.text());
        String all = ReplyLayoutText.render(l);
        assertTrue(all.indexOf(ReplyLayoutText.PAGE_BREAK) < all.indexOf("INTERNAL REVIEW SHEET - NOT FOR FILING"));
        for (String s : new String[] {"A. ITEMS TO FILL BEFORE FILING", "B. POINTS FOR THE PARTNER", "C. FILING CHECKLIST"}) {
            assertTrue(all.indexOf(s) > all.indexOf("INTERNAL REVIEW SHEET"), s);
        }
    }

    @Test
    void reply_hasNoOldLabels_rawTokens_orInternalText() {
        String t = replyText(layout());
        assertFalse(Pattern.compile("Issue\\s*#").matcher(t).find(), t);
        assertFalse(Pattern.compile("(?i)\\bSections?\\s+0\\d\\b").matcher(t).find(), t);
        assertFalse(Pattern.compile("(?m)^\\s*0\\d\\s").matcher(t).find(), t);
        assertFalse(t.contains("{{") || t.contains("}}") || t.contains("[[") || t.contains("]]"), t);
        for (String internal : new String[] {"CARD-", "TPL-", "CHECK_", "block:", "warn:", "INR", "Match Status", "Template="}) {
            assertFalse(t.contains(internal), internal + " in the reply:\n" + t);
        }
    }

    @Test
    void issueHeadings_titlesAmounts_andEveryIssueHasContent() {
        ReplyLayout l = layout();
        List<String> headings = l.reply().stream().filter(b -> b instanceof Para p && p.style() == Style.HEADING).map(b -> ((Para) b).text()).toList();
        assertEquals("Issue 1: Blocked credit under Section 17(5)(d) on cement and steel (Rs 28,64,200)", headings.get(0));
        assertEquals("Issue 2: Non-reversal of common credit under Rule 42 on sale of duty credit scrips (Rs 3,96,480)", headings.get(1));
        assertTrue(headings.contains("Issue 6: No valid DIN on the notice"), headings.toString());

        String t = replyText(l);
        // the skipped draft
        assertTrue(t.contains("[Partner to draft: the AI draft for this issue was skipped (cost cap)]"), t);
        // Issue 5 had no grounds block: its legal submissions still render under its heading
        int h5 = t.indexOf("Issue 5: ");
        int h6 = t.indexOf("PROCEDURAL OBJECTIONS");
        String issue5 = t.substring(h5, h6);
        assertTrue(issue5.contains("Legal submissions") && issue5.contains("Rule 86B does not apply"), issue5);
        assertFalse(issue5.contains("Facts"), issue5);
        // continuous numbering under Part 3 with no gaps
        List<String> nums = l.reply().stream().filter(b -> b instanceof Para p && p.number() != null && p.number().startsWith("3."))
                .map(b -> ((Para) b).number()).toList();
        for (int k = 0; k < nums.size(); k++) assertEquals("3." + (k + 1), nums.get(k), nums.toString());
        // the old cross-reference to block B1 points to its new number
        assertTrue(t.matches("(?s).*see para 3\\.\\d+\\..*"), t);
    }

    @Test
    void paraWiseTable_pointsToExistingIssueHeadings() {
        ReplyLayout l = layout();
        Table para = l.reply().stream().filter(b -> b instanceof Table t && t.header().get(0).startsWith("Para")).map(b -> (Table) b)
                .findFirst().orElseThrow();
        Set<String> headings = new HashSet<>();
        l.reply().stream().filter(b -> b instanceof Para p && p.style() == Style.HEADING)
                .forEach(b -> headings.add(((Para) b).text().replaceFirst(":.*$", "")));
        Pattern ref = Pattern.compile("Issues? ([\\d, and]+)");
        for (List<String> row : para.rows()) {
            String reply = row.get(1);
            assertTrue(reply.matches("Denied\\. See the repl(y|ies) to Issues? .*\\.|Admitted\\.|Noted\\.|Denied, for the reasons set out in this reply\\."),
                    row.toString());
            Matcher m = ref.matcher(reply);
            if (m.find()) {
                for (String n : m.group(1).split("\\D+")) {
                    if (!n.isEmpty()) assertTrue(headings.contains("Issue " + n), "Para reply points to a missing heading: " + row + " " + headings);
                }
            }
        }
        assertEquals(List.of("Para 2", "Denied. See the reply to Issue 1."), para.rows().get(1));
        assertEquals(List.of("Para 6", "Denied. See the reply to Issue 5."), para.rows().get(5));
        assertEquals("Noted.", para.rows().get(0).get(1));
    }

    @Test
    void placeholders_areShortHumanPhrases_numbersIndian_andTheLetterIsAddressed() {
        String t = replyText(layout());
        assertTrue(t.contains("[date of receipt]"), t);
        assertTrue(t.contains("[which exception]"), t);
        assertTrue(t.contains("[VERIFY: case name and citation]"), t);
        assertTrue(t.contains("Rs 1,12,00,000"), t);
        assertFalse(t.contains("11200000"), t);
        assertTrue(t.contains("The Joint Commissioner of Central Tax"), t);
        assertTrue(t.contains("Bengaluru South Commissionerate, GST Bhavan, Bengaluru 560047"), t);
        assertTrue(t.contains("No. 7, Hosur Main Road, Bommanahalli, Bengaluru 560068"), t);
        assertTrue(t.contains("GSTIN: 29MMMNN9012M1Z7"), t);
        assertTrue(t.contains("C.No. IV/16/118/2024-25 Adjn. dated 29-11-2024 under Section 74(1)"), t);
        // summary table: amounts right aligned, the total row, and the prayer as (a), (b), (c)
        ReplyLayout l = layout();
        Table summary = l.reply().stream().filter(b -> b instanceof Table x && x.header().contains("Company's position")).map(b -> (Table) b)
                .findFirst().orElseThrow();
        assertEquals(Set.of(2), summary.rightAligned());
        assertEquals("Rs 44,94,680", summary.rows().get(summary.rows().size() - 1).get(2));
        List<String> prayer = l.reply().stream().filter(b -> b instanceof Para p && p.style() == Style.ITEM).map(b -> ((Para) b).number()).toList();
        assertTrue(prayer.containsAll(List.of("(a)", "(b)", "(c)")), prayer.toString());
    }

    @Test
    void reviewSheet_listsItemsToFill_codeCheckPoints_andChecklist_withoutTheDeveloperTable() {
        ReplyLayout l = layout();
        String review = ReplyLayoutText.render(new ReplyLayout(List.of(), l.review()));
        assertTrue(review.contains("date of receipt | Part 1 (Background), para 1.3; Issue 1, para 3.1"), review);
        assertTrue(review.contains("din=defect"), review);
        assertTrue(review.contains("Form GST DRC-06"), review);
        assertFalse(review.contains("CHECK_ALL_SECTIONS_PRESENT") || review.contains("PASS |"), review);
        // a reply stored before the layout metadata existed still lays out (issues from the headings)
        List<ReplyLayoutBuilder.Section> plain = List.of(new ReplyLayoutBuilder.Section(4, "04", "<h3>Issue #1 Grounds of Defence</h3><p>4.1 Text.</p>"),
                new ReplyLayoutBuilder.Section(5, "05", "<ol><li><strong>Para 1:</strong> Denied. Please refer to the reply to Issue #1 at para 4.1.</li></ol>"));
        ReplyLayout old = ReplyLayoutBuilder.build(plain);
        String ot = ReplyLayoutText.renderReply(old);
        assertTrue(ot.contains("Issue 1: Issue 1") || ot.contains("Issue 1"), ot);
        assertTrue(ot.contains("Para 1 | Denied. See the reply to Issue 1."), ot);
    }

    @Test
    void humanise() {
        assertEquals("[date of receipt]", ReplyLayout.plain(ReplyLayout.humanise("[[MISSING: date_of_receipt]]")));
        assertEquals("[scrip type]", ReplyLayout.plain(ReplyLayout.humanise("{{issue.scrip_type}}")));
        assertEquals("[VERIFY: citation]", ReplyLayout.plain(ReplyLayout.humanise("[[VERIFY: citation]]")));
        assertEquals("Rs 1,12,00,000 and Rs 4,00,000", ReplyLayout.humanise("Rs. 11200000 and INR 400000"));
        assertEquals("Rs 44,94,680", ReplyLayout.humanise("Rs 44,94,680"));
    }

    @SuppressWarnings("unused")
    private static List<Block> reply(ReplyLayout l) {
        return l.reply();
    }
}
