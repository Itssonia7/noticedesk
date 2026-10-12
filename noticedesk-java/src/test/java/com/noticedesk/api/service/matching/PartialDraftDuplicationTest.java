package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.TemplateFillService.FilledBlock;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live run, notice 004: the partial writer reused template block BLK-011-04A (para 4.1) and then
 * paraphrased the same point in its own text (para 4.3). The paraphrase must be dropped with an info
 * flag; genuinely new AI paragraphs stay.
 */
class PartialDraftDuplicationTest {

    /** BLK-011-04A as filled (same text as seeds/dev/seed_reply_and_stage_templates_dev.sql). */
    private static final String BLK_011_04A = "<p>[DEV - not CA reviewed] The interest demand of Rs 2,40,000 under Section 50 has been "
            + "computed on gross tax liability instead of net cash liability. As per the proviso to Section 50(1), interest is "
            + "payable only on the portion of tax paid by debiting the electronic cash ledger.</p>";
    private static final String BLK_011_06A = "<p>[DEV - not CA reviewed] Proviso to Section 50(1) inserted retrospectively from "
            + "01-07-2017 mandates calculation of interest solely on net cash tax liability.</p>";

    /** The 004-style AI restatement of BLK-011-04A. */
    private static final String AI_RESTATEMENT = "<p>The interest demand of Rs 2,40,000 under Section 50 has been computed on the gross tax "
            + "liability instead of the net cash liability. Under the proviso to Section 50(1), interest is payable only on the "
            + "portion of tax paid by debiting the electronic cash ledger.</p>";
    private static final String AI_NEW_FACTS = "<p>For the period April 2022 to March 2023 the Noticee discharged Rs 18,00,000 of its "
            + "output liability through the electronic credit ledger and only Rs 3,00,000 in cash; the notice applies the rate of 18% "
            + "to the entire liability of Rs 21,00,000.</p>";
    private static final String AI_NEW_DELAY = "<p>The returns were filed late only because the GSTN portal was unavailable on the due "
            + "date, as shown by the screenshots at [[MISSING: portal_outage_evidence]].</p>";

    @Test
    void jaccardSeparatesRestatementFromNewText() {
        assertTrue(ParagraphOverlap.jaccard(AI_RESTATEMENT, BLK_011_04A) >= ParagraphOverlap.RESTATEMENT_THRESHOLD);
        assertTrue(ParagraphOverlap.jaccard(AI_NEW_FACTS, BLK_011_04A) < ParagraphOverlap.RESTATEMENT_THRESHOLD);
        assertTrue(ParagraphOverlap.jaccard(AI_NEW_DELAY, BLK_011_04A) < ParagraphOverlap.RESTATEMENT_THRESHOLD);
        assertEquals(1.0, ParagraphOverlap.jaccard("<p>[DEV - not CA reviewed] Same words here.</p>", "Same words here"), 1e-9);
        assertEquals(0.0, ParagraphOverlap.jaccard("", BLK_011_04A));
    }

    @Test
    void dropRestatementsKeepsOtherParagraphsAndNonParagraphHtml() {
        ParagraphOverlap.Filtered f = ParagraphOverlap.dropRestatements(
                AI_NEW_FACTS + AI_RESTATEMENT + "<ul><li>Item</li></ul>", List.of(BLK_011_04A), ParagraphOverlap.RESTATEMENT_THRESHOLD);
        assertEquals(1, f.dropped());
        assertEquals(AI_NEW_FACTS + "<ul><li>Item</li></ul>", f.html());

        ParagraphOverlap.Filtered none = ParagraphOverlap.dropRestatements(AI_NEW_FACTS, List.of(), 0.6);
        assertEquals(0, none.dropped());
        assertEquals(AI_NEW_FACTS, none.html());
    }

    @Test
    void notice004StyleReusedBlockIsNotRestatedByAiText() throws IOException {
        FilledTemplateResult ft = new FilledTemplateResult("TPL-011", 1, "CARD-011",
                "Interest under Section 50 is payable only on the net cash liability.",
                List.of(new FilledBlock("BLK-011-04A", 4, BLK_011_04A, List.of("CIT-002"), List.of(), List.of()),
                        new FilledBlock("BLK-011-06A", 6, BLK_011_06A, List.of("CIT-002"), List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of("Electronic cash ledger extract"), List.of(), false);
        // partial writer: anchors to BLK-011-04A, adds new facts, then restates the block; a second
        // unanchored fragment adds a new ground. Section 6 restates BLK-011-04A again.
        AiIssueContent ai = new AiIssueContent(1, "partial", List.of(
                new AiFragment(4, "BLK-011-04A", AI_NEW_FACTS + AI_RESTATEMENT),
                new AiFragment(4, null, AI_NEW_DELAY),
                new AiFragment(6, "BLK-011-06A", AI_RESTATEMENT)),
                List.of(), "Interest is payable only on the net cash liability.");

        AssembledReplyResult r = assemble(ft, ai);
        String sec04 = r.sections().get(3).bodyHtml();
        String sec06 = r.sections().get(5).bodyHtml();

        assertTrue(sec04.contains("4.1 [DEV - not CA reviewed] The interest demand of Rs 2,40,000"), sec04);
        assertTrue(sec04.contains("4.2 For the period April 2022 to March 2023"), sec04);
        assertTrue(sec04.contains("4.3 The returns were filed late only because"), sec04);
        assertFalse(sec04.contains("Under the proviso to Section 50(1), interest is payable"), "AI paraphrase of BLK-011-04A must be dropped");
        assertFalse(sec04.contains("4.4"), sec04);

        assertTrue(sec06.contains("6.1 [DEV - not CA reviewed] Proviso to Section 50(1) inserted retrospectively"), sec06);
        assertFalse(sec06.contains("Under the proviso to Section 50(1), interest is payable"), "restating a block from another section is dropped too");

        assertTrue(r.flags().contains("info:ai_paragraph_restates_template_block"));
        String sec13 = r.sections().get(12).bodyHtml();
        assertTrue(sec13.contains("removed because they restated a template block already printed"), sec13);
    }

    @Test
    void unreusedBlockDoesNotSuppressAiText() throws IOException {
        // the writer did not anchor to BLK-011-04A, so the block is not printed and the AI text must stay
        FilledTemplateResult ft = new FilledTemplateResult("TPL-011", 1, "CARD-011", "Summary.",
                List.of(new FilledBlock("BLK-011-04A", 4, BLK_011_04A, List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of(), false);
        AiIssueContent ai = new AiIssueContent(1, "partial", List.of(new AiFragment(4, null, AI_RESTATEMENT)), List.of(), "Summary.");

        AssembledReplyResult r = assemble(ft, ai);
        String sec04 = r.sections().get(3).bodyHtml();
        assertTrue(sec04.contains("4.1 The interest demand of Rs 2,40,000"), sec04);
        assertFalse(sec04.contains("[DEV - not CA reviewed] The interest demand"), sec04);
        assertFalse(r.flags().contains("info:ai_paragraph_restates_template_block"));
    }

    @Test
    void partialPromptForbidsRestatingReusedBlocks() throws IOException {
        try (var in = getClass().getResourceAsStream("/prompts/partial_drafting_v2.md")) {
            String prompt = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(prompt.contains("NEVER RESTATE A REUSED BLOCK"));
            assertTrue(prompt.contains("Return only NEW paragraphs"));
        }
    }

    private AssembledReplyResult assemble(FilledTemplateResult ft, AiIssueContent ai) {
        ReplyAssemblyService svc = new ReplyAssemblyService(null, null, new ObjectMapper());
        svc.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                Map.of("08", "<p>Cross exam</p>", "12", "<p>Prayer</p>", "14", "<p>Client summary</p>", "15", "<p>Checklist</p>"),
                null, null, null));
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF-004", "DIN-004", "2023-10-15", "30 days", "2022-23", "2022-23", 240000.0, "Proper Officer");
        MatchedIssue issue = new MatchedIssue(1, "partial", List.of("CARD-011"), "Interest on gross liability", List.of(),
                Map.of("period", "2022-23", "amount", 240000.0), List.of(), List.of());
        MatchingResult mr = new MatchingResult(info, List.of(issue), Map.of(), List.of(), List.of(), "hash", 1, 1, 0, 0);
        DraftingInput in = new DraftingInput(UUID.randomUUID(), "formal", null, "M/s Synthetic Traders", null, null, "Regular",
                "09SSSTT1234S1Z0", null, "CGST Act", "2022-23", null, Map.of("issue_date", "2023-10-15", "section", "Section 73"),
                Map.of(), "OCR", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        return svc.assembleReply(mr, List.of(ft), in, "scn_73", Map.of(1, ai), null, List.of());
    }
}
