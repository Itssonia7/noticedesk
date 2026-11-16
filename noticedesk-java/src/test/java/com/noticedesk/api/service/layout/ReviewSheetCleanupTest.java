package com.noticedesk.api.service.layout;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.testsupport.SyntheticS73Notice;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live run at c82df8b on the synthetic Section 73 notice, internal review sheet: "Citations marked VERIFY" printed a
 * bare "[VERIFY]"; "Facts to confirm with the client" listed the same CONFIRM facts up to three times; developer lines
 * (catalogue shortlist, all-ok code checks, template variable read-outs) were partner points. Offline replay.
 */
class ReviewSheetCleanupTest {

    private static final String FACT = "the packaging machinery is used in the course of the noticee's business";
    private static final List<String> NOTES = List.of(
            "Catalogue shortlist: 42 of 269 cards shown to the matcher (labels 30, title pass 12).",
            "Code defect checks: din_rfn=ok (central-tax document dated 05-11-2024 carries a DIN); signature=ok (digitally signed);"
                    + " limitation=ok (FY 2021-22 within Section 73(10)).",
            "Issue #5: template variables read from the document: issue.late_fee_status=has been paid.",
            "Template variables the document does not state (left as [[MISSING]]): issue.order_date.",
            "Issue #5: variable rejected: issue.late_fee_status expected text (the document value is not of that kind; left as [[MISSING]]).",
            "Issue #3: AI writer call not made (per-notice cost cap: worst case of this call INR 2.10 + spent INR 23.00 exceeds the cap"
                    + " of INR 24.86); left to the partner.",
            "Issue #2 fact to confirm with the client: " + FACT + " (supports: The machinery is used in the business.)",
            "Template TPL-X wording to be reviewed by the partner.");

    private ReplyLayout layout(List<String> notes) {
        List<MatchedIssue> issues = new ArrayList<>(SyntheticS73Notice.issues());
        MatchedIssue two = issues.get(1);
        issues.set(1, new MatchedIssue(2, "partial", "demand", two.cardIds(), List.of(), "synthetic", List.of(), two.facts(),
                two.noticeParas(), List.of(), List.of(FACT, "the inputs were received at the registered premises"), null));
        Map<Integer, AiIssueContent> ai = SyntheticS73Notice.aiFor(issues);
        ai.put(1, new AiIssueContent(1, "partial", List.of(
                new AiFragment(4, null, "<p>The ITC was availed on invoices reflected in the suppliers' returns.</p>"),
                new AiFragment(6, null, "<p>Credit cannot be denied for a mismatch with GSTR-2B alone; see Suncraft Energy Pvt Ltd v."
                        + " Assistant Commissioner, State Tax (2023) [[VERIFY: case name and citation]].</p>")), List.of(), "Not sustainable."));
        ai.put(2, new AiIssueContent(2, "partial", List.of(
                new AiFragment(4, null, "<p>If [[CONFIRM: " + FACT + "]], the credit is admissible under Section 16(1).</p>"),
                new AiFragment(6, null, "<p>The registration certificate does not limit the credit to listed HSN codes [VERIFY].</p>")),
                List.of(), "Not sustainable."));
        ai.put(3, new AiIssueContent(3, "none (AI draft skipped, cost cap)", List.of(
                new AiFragment(4, null, "<p>[[PARTNER: AI draft skipped — cost cap]]</p>")), List.of(), "[[PARTNER: AI draft skipped — cost cap]]"));
        AssembledReplyResult r = SyntheticS73Notice.assemble(SyntheticS73Notice.matching(issues, SyntheticS73Notice.TAX_TOTAL), ai, notes);
        // the pipeline appends its marker lists in place of the check-results placeholder
        List<AssembledSection> sections = new ArrayList<>();
        for (AssembledSection s : r.sections()) {
            sections.add(s.num() != 13 ? s : new AssembledSection(13, s.title(), s.bodyHtml().replace("<!-- CHECK_RESULTS_PLACEHOLDER -->",
                    "<p><strong>Facts to confirm with the client:</strong></p><ul><li>" + FACT + " (severity: warn)</li><li>Issue #2: "
                            + FACT + " (severity: warn)</li></ul>")));
        }
        return ReplyLayoutBuilder.build(sections.stream().map(s -> new ReplyLayoutBuilder.Section(s.num(), s.title(), s.bodyHtml())).toList());
    }

    private static String review(ReplyLayout l) {
        String all = ReplyLayoutText.render(l);
        return all.substring(all.indexOf(ReplyLayout.REVIEW_TITLE));
    }

    private static String part(String review, String from, String to) {
        int a = review.indexOf(from);
        int b = to != null ? review.indexOf(to, a + 1) : review.length();
        assertTrue(a >= 0 && b > a, from + " / " + to + ":\n" + review);
        return review.substring(a, b);
    }

    @Test
    void verifyItems_nameTheIssueParaAndAuthority() {
        String review = review(layout(NOTES));
        String verify = part(review, "Citations marked VERIFY", "Facts to confirm with the client");
        assertTrue(verify.matches("(?s).*Issue 1, para 3\\.\\d+: Suncraft Energy Pvt Ltd v\\. Assistant Commissioner, State Tax \\(2023\\).*"), verify);
        assertTrue(verify.matches("(?s).*Issue 2, para 3\\.\\d+: The registration certificate does not limit the credit to listed HSN codes.*"), verify);
        assertFalse(verify.matches("(?s).*-\\s*\\[VERIFY[^\\n]*\\]\\s*\\n.*"), "no bare [VERIFY] item: " + verify);
    }

    @Test
    void confirmFacts_listedOnceGroupedByIssue_mergedWithTheCardList_andStillItemsToFill() {
        String review = review(layout(NOTES));
        String facts = part(review, "Facts to confirm with the client", "Procedural objections the partner may add");
        assertEquals(1, count(facts, FACT), facts);
        assertTrue(facts.contains("Issue 2: " + FACT), facts);
        assertTrue(facts.contains("Issue 2: the inputs were received at the registered premises"), facts);
        String points = part(review, "B. POINTS FOR THE PARTNER", "Facts to confirm with the client");
        assertEquals(0, count(points, FACT), "not repeated among the partner points: " + points);
        String items = part(review, "A. ITEMS TO FILL BEFORE FILING", "B. POINTS FOR THE PARTNER");
        assertTrue(items.contains("CONFIRM: " + FACT), "the CONFIRM row stays in A: " + items);
    }

    @Test
    void developerLines_goToTheRunSummary_costCapPointReworded() {
        ReplyLayout l = layout(NOTES);
        String review = review(l);
        for (String dev : List.of("Catalogue shortlist", "Code defect checks", "=ok", "template variables read from the document",
                "Template variables the document does not state", "variable rejected")) {
            assertFalse(review.contains(dev), dev + " must not be in the review sheet:\n" + review);
        }
        assertTrue(review.contains("Issue 3 was not drafted because the per-notice cost limit was reached; partner to draft."), review);
        assertFalse(review.contains("AI writer call not made"), review);
        assertTrue(review.contains("Template TPL-X wording to be reviewed by the partner."), "other partner notes stay");

        List<String> dev = ReplyLayoutBuilder.developerNotes(sectionsFromNotes(NOTES));
        assertEquals(6, dev.size(), "five developer lines plus the technical cost-cap line behind the reworded point: " + dev);
        assertTrue(dev.stream().anyMatch(d -> d.startsWith("Issue #3: AI writer call not made (per-notice cost cap")), dev.toString());
        assertTrue(dev.get(0).startsWith("Catalogue shortlist:"));
        assertTrue(dev.stream().anyMatch(d -> d.contains("variable rejected: issue.late_fee_status expected text")), dev.toString());
    }

    @Test
    void codeCheckLine_keptOnlyForADefect_withOnlyTheDefectChecks() {
        List<String> notes = new ArrayList<>(NOTES);
        notes.set(1, "Code defect checks: din_rfn=defect (central-tax document dated 05-11-2024: DIN field blank or a placeholder);"
                + " signature=ok (digitally signed).");
        String review = review(layout(notes));
        String checks = part(review, "Code checks that disagree with the matcher or need a decision", "Citations marked VERIFY");
        assertTrue(checks.contains("Code defect checks: din_rfn=defect (central-tax document dated 05-11-2024: DIN field blank or a placeholder)."), checks);
        assertFalse(checks.contains("signature"), checks);
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }

    private static List<ReplyLayoutBuilder.Section> sectionsFromNotes(List<String> notes) {
        StringBuilder sb = new StringBuilder("<p><strong>Stage 3 Notes:</strong></p><ul>");
        for (String n : notes) sb.append("<li>").append(n).append("</li>");
        sb.append("</ul>");
        return List.of(new ReplyLayoutBuilder.Section(13, "13", sb.toString()));
    }
}
