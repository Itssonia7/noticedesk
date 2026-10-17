package com.noticedesk.api.service;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.model.matching.IssueCard;
import com.noticedesk.api.service.V7DraftingPipelineService.StageCorrection;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.matching.StageResolver;
import com.noticedesk.api.service.matching.StageResolver.ResponseStage;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post-matching response-stage correction (live run: an RFD-06 refund rejection ORDER matched to the reply-stage
 * refund card NDK-225 although its appeal-stage sibling NDK-224 exists).
 */
class V7StageCorrectionTest {

    private static IssueCard card(String id, String title, List<String> stages, List<String> notThisIf) {
        return new IssueCard(id, 1, title, "summary", List.of("Section 54"), null, null, stages, List.of(), notThisIf,
                null, "active", null, null, null);
    }

    private static final IssueCard NDK_225 = card("NDK-225", "Refund rejected: reply to RFD-08", List.of("scn_reply"),
            List.of("Input is an RFD-06 order (refund already rejected): use NDK-224 (appeal)."));
    private static final IssueCard NDK_224 = card("NDK-224", "Appeal against RFD-06 refund rejection", List.of("order_appeal"),
            List.of("Input is an RFD-08 notice awaiting reply: use NDK-225."));
    private static final IssueCard NDK_221 = card("NDK-221", "Refund time-bar: reply to RFD-08", List.of("scn_reply"), List.of());
    private static final IssueCard NDK_230 = card("NDK-230", "APL-01 appeal: refund interest", List.of(), List.of());
    private static final IssueCard NDK_231 = card("NDK-231", "Appeal: refund of accumulated ITC", List.of("order_appeal"), List.of());

    private static Map<String, IssueCard> catalogue(IssueCard... cards) {
        return CatalogueService.byId(List.of(cards));
    }

    private static MatchedIssue issue(int no, String primary, List<String> alternates) {
        return new MatchedIssue(no, "full", "demand", List.of(primary), alternates, "why", List.of(),
                Map.of("period", "2022-23", "legal_section", "Section 54"), List.of(), List.of("hedged"));
    }

    @Test
    void appealStageClassFromStagesOrTitle() {
        assertTrue(V7DraftingPipelineService.isAppealStageCard(NDK_224));
        assertTrue(V7DraftingPipelineService.isAppealStageCard(NDK_230), "title starts with APL-01");
        assertFalse(V7DraftingPipelineService.isAppealStageCard(NDK_225));
        assertEquals("reply", V7DraftingPipelineService.stageClass(NDK_221));
    }

    @Test
    void rfd06OrderSwapsReplyCardForItsAppealSibling() {
        List<MatchedIssue> issues = List.of(issue(1, "NDK-225", List.of("NDK-221")));
        StageCorrection sc = V7DraftingPipelineService.correctResponseStage(issues, StageResolver.resolve("RFD-06", null),
                catalogue(NDK_221, NDK_224, NDK_225));

        MatchedIssue fixed = sc.issues().get(0);
        assertEquals("NDK-224", fixed.primaryCardId());
        assertEquals(List.of("NDK-225", "NDK-221"), fixed.alternateCardIds(), "old primary first among the alternates");
        assertEquals("full", fixed.status(), "status kept");
        assertTrue(fixed.flags().contains("stage_corrected: NDK-225 -> NDK-224"));
        assertTrue(fixed.flags().contains("hedged"), "earlier issue flags kept");
        assertEquals(1, sc.swaps().size());
        assertEquals(new StageCorrection.Swap(1, "NDK-225", "NDK-224"), sc.swaps().get(0));
        assertTrue(sc.mismatches().isEmpty());
    }

    @Test
    void appealSiblingFromTheMatchersAlternates() {
        // NDK-221 names no sibling; the matcher listed the appeal card as an alternate
        List<MatchedIssue> issues = List.of(issue(1, "NDK-221", List.of("NDK-231")));
        StageCorrection sc = V7DraftingPipelineService.correctResponseStage(issues, ResponseStage.APPEAL,
                catalogue(NDK_221, NDK_231));
        assertEquals("NDK-231", sc.issues().get(0).primaryCardId());
        assertEquals(List.of("NDK-221"), sc.issues().get(0).alternateCardIds());
    }

    @Test
    void noSiblingOnlyWarns() {
        List<MatchedIssue> issues = List.of(issue(1, "NDK-221", List.of()));
        StageCorrection sc = V7DraftingPipelineService.correctResponseStage(issues, ResponseStage.APPEAL,
                catalogue(NDK_221, NDK_224, NDK_225));
        MatchedIssue kept = sc.issues().get(0);
        assertEquals("NDK-221", kept.primaryCardId());
        assertTrue(kept.flags().contains("stage_mismatch: appeal vs reply"));
        assertTrue(sc.swaps().isEmpty());
        assertEquals(1, sc.mismatches().size());
        assertTrue(sc.mismatches().get(0).candidates().isEmpty());
    }

    @Test
    void twoCandidateSiblingsOnlyWarn() {
        List<MatchedIssue> issues = List.of(issue(1, "NDK-225", List.of("NDK-231")));
        StageCorrection sc = V7DraftingPipelineService.correctResponseStage(issues, ResponseStage.APPEAL,
                catalogue(NDK_224, NDK_225, NDK_231));
        assertEquals("NDK-225", sc.issues().get(0).primaryCardId());
        assertEquals(List.of("NDK-224", "NDK-231"), sc.mismatches().get(0).candidates());
    }

    @Test
    void mirrorSwapOnAReplyStageInput() {
        // an RFD-08 notice that still awaits a reply, matched to the appeal card
        List<MatchedIssue> issues = List.of(issue(1, "NDK-224", List.of()));
        StageCorrection sc = V7DraftingPipelineService.correctResponseStage(issues, StageResolver.resolve("RFD-08", null),
                catalogue(NDK_224, NDK_225));
        assertEquals("NDK-225", sc.issues().get(0).primaryCardId());
        assertEquals(List.of("NDK-224"), sc.issues().get(0).alternateCardIds());
        assertTrue(sc.issues().get(0).flags().contains("stage_corrected: NDK-224 -> NDK-225"));
    }

    @Test
    void rightStageRevocationOrUnknownLeavesTheIssuesUnchanged() {
        List<MatchedIssue> issues = List.of(issue(1, "NDK-224", List.of()));
        Map<String, IssueCard> cards = catalogue(NDK_224, NDK_225);
        assertSame(issues, V7DraftingPipelineService.correctResponseStage(issues, ResponseStage.APPEAL, cards).issues());
        assertSame(issues, V7DraftingPipelineService.correctResponseStage(issues, ResponseStage.REVOCATION, cards).issues());
        assertSame(issues, V7DraftingPipelineService.correctResponseStage(issues, null, cards).issues());
        // an issue whose card is not in the catalogue is left alone
        List<MatchedIssue> unknown = List.of(issue(1, "NDK-999", List.of()));
        assertSame(unknown, V7DraftingPipelineService.correctResponseStage(unknown, ResponseStage.REPLY, cards).issues());
    }

    @Test
    void pipelineStepAddsFlagDetailAndPartnerNote() {
        V7DraftingPipelineService pipeline = new V7DraftingPipelineService(null, null, null, null, null, null);
        pipeline.setCatalogueService(new CatalogueService(null, null, null) {
            @Override
            public Map<String, IssueCard> cardsById() {
                return catalogue(NDK_221, NDK_224, NDK_225);
            }
        });
        List<String> notes = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        Map<String, Map<String, Object>> details = new LinkedHashMap<>();
        String heading = "FORM-GST-RFD-06 [See rule 92] Order rejecting the refund claim";

        List<MatchedIssue> out = pipeline.applyStageCorrection(List.of(issue(1, "NDK-225", List.of()), issue(2, "NDK-221", List.of())),
                "scn_73", heading, UUID.randomUUID(), notes, flags, details);

        assertEquals("NDK-224", out.get(0).primaryCardId());
        assertEquals("NDK-221", out.get(1).primaryCardId());
        assertTrue(flags.contains(V7DraftingPipelineService.FLAG_STAGE_CORRECTED));
        assertTrue(flags.contains(V7DraftingPipelineService.FLAG_STAGE_MISMATCH));
        assertEquals(List.of("NDK-225 -> NDK-224"), details.get("info:stage_corrected").get("corrections"));
        assertEquals(List.of("NDK-221: appeal vs reply"), details.get("warn:stage_mismatch").get("mismatches"));
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("Issue #1: stage corrected from NDK-225 to NDK-224")), notes.toString());
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("Issue #2: card NDK-221 is reply-stage")), notes.toString());
    }

    @Test
    void pipelineStepWithoutCatalogueOrStageDoesNothing() {
        V7DraftingPipelineService pipeline = new V7DraftingPipelineService(null, null, null, null, null, null);
        List<MatchedIssue> issues = List.of(issue(1, "NDK-225", List.of()));
        Set<String> flags = new LinkedHashSet<>();
        assertSame(issues, pipeline.applyStageCorrection(issues, "RFD-06", null, null, new ArrayList<>(), flags, new HashMap<>()));
        pipeline.setCatalogueService(new CatalogueService(null, null, null) {
            @Override
            public Map<String, IssueCard> cardsById() {
                return catalogue(NDK_224, NDK_225);
            }
        });
        assertSame(issues, pipeline.applyStageCorrection(issues, "needs_review", "no form here", null, new ArrayList<>(), flags, new HashMap<>()));
        assertTrue(flags.isEmpty());
    }
}
