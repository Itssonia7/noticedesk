package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.service.matching.CodeDefectMerger.Merged;
import com.noticedesk.api.service.matching.CodeDefectMerger.Outcome;
import com.noticedesk.api.service.matching.DefectChecker.CodeDefect;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Merge of the code defect checks into the matched issues (after matching, before the stage correction). */
class CodeDefectMergerTest {

    private static MatchedIssue demand(int no, String card) {
        return new MatchedIssue(no, "full", "demand", List.of(card), List.of(), "w", List.of(),
                Map.of("period", "2019-20", "amount", 1000.0), List.of(String.valueOf(no + 1)), List.of(), List.of(), null);
    }

    private static MatchedIssue consequential(int no, String card, int parent) {
        return new MatchedIssue(no, "full", "consequential", List.of(card), List.of(), "w", List.of(),
                Map.of("period", "2019-20"), List.of("5"), List.of(), List.of(), parent);
    }

    private static MatchedIssue defect(int no, String card, String why) {
        return new MatchedIssue(no, card != null ? "full" : "none", "defect", card != null ? List.of(card) : List.of(), List.of(), why,
                List.of(), Map.of("period", "2019-20"), List.of("header"), List.of(), List.of(), null);
    }

    private static final CodeDefect DIN_DEFECT = new CodeDefect(DefectChecker.DIN, "defect", "central-tax document dated 02.05.2024: DIN placeholder",
            "NDK-141", "NDK-142");
    private static final CodeDefect DIN_OK = new CodeDefect(DefectChecker.DIN, "ok", "central-tax document dated 02.05.2024 carries a DIN",
            "NDK-141", "NDK-142");
    private static final CodeDefect SIGNATURE_NA = new CodeDefect(DefectChecker.SIGNATURE, "ok", "not applicable: x", "NDK-143", "NDK-144");
    private static final CodeDefect LIMITATION_NO_CARD = new CodeDefect(DefectChecker.LIMITATION_73, "defect",
            "SCN 02.06.2024 vs Section 73 SCN deadline 31.05.2024 for FY 2019-20: time-barred", null, null);

    private static Merged merge(List<MatchedIssue> issues, boolean appeal, CodeDefect... defects) {
        return CodeDefectMerger.merge(issues, Map.of("2", List.of(1), "3", List.of(2)), List.of(defects), appeal, id -> true, "2019-20");
    }

    @Test
    void addsAMissedDefectOnTheReplyCard_afterTheDemandIssues() {
        List<MatchedIssue> issues = List.of(demand(1, "NDK-006"), demand(2, "NDK-007"));
        Merged m = merge(issues, false, DIN_DEFECT);

        assertEquals(3, m.issues().size());
        MatchedIssue added = m.issues().get(2);
        assertEquals(3, added.issueNo());
        assertTrue(added.isDefect());
        assertEquals("full", added.status());
        assertEquals(List.of("NDK-141"), added.cardIds());
        assertEquals("Code check din: central-tax document dated 02.05.2024: DIN placeholder", added.why());
        assertEquals(List.of("header"), added.noticeParas());
        assertEquals(List.of(CodeDefectMerger.FLAG_ADDED), added.flags());
        assertEquals("2019-20", added.facts().get("period"), "the template fill needs the period");
        assertEquals(List.of(new Outcome("din", "added", 3, "NDK-141", DIN_DEFECT.basis())), m.outcomes());
    }

    @Test
    void appealStageChoosesTheAppealCard() {
        Merged m = merge(List.of(demand(1, "NDK-006")), true, DIN_DEFECT);
        assertEquals(List.of("NDK-142"), m.issues().get(1).cardIds());
    }

    @Test
    void noCardForTheRegime_isStatusNoneWithAPartnerPlaceholder() {
        Merged m = merge(List.of(demand(1, "NDK-006")), false, LIMITATION_NO_CARD);
        MatchedIssue added = m.issues().get(1);
        assertEquals("none", added.status());
        assertTrue(added.cardIds().isEmpty());
        assertEquals("[[PARTNER: SCN 02.06.2024 vs Section 73 SCN deadline 31.05.2024 for FY 2019-20: time-barred; no catalogue card for this regime]]",
                added.facts().get(CodeDefectMerger.FACT_PARTNER_NOTE));
        // a card the catalogue does not hold is treated the same way
        Merged unknown = CodeDefectMerger.merge(List.of(demand(1, "NDK-006")), Map.of(), List.of(DIN_DEFECT), false,
                id -> !id.equals("NDK-141"), "2019-20");
        assertEquals("none", unknown.issues().get(1).status());
        assertNotNull(unknown.issues().get(1).facts().get(CodeDefectMerger.FACT_PARTNER_NOTE));
    }

    @Test
    void matcherDefectOnTheChecksCard_isConfirmed_notDuplicated() {
        List<MatchedIssue> issues = List.of(demand(1, "NDK-006"), defect(2, "NDK-142", "no DIN"));
        Merged m = merge(issues, false, DIN_DEFECT);
        assertEquals(2, m.issues().size());
        assertTrue(m.issues().get(1).flags().contains(CodeDefectMerger.FLAG_CONFIRMED));
        assertEquals("confirmed", m.outcomes().get(0).action());
        assertEquals(2, m.outcomes().get(0).issueNo());
    }

    @Test
    void matcherDefectNamingTheCheckInItsWhy_isConfirmed() {
        // the matcher used another card but its why points at defect_checks.limitation
        List<MatchedIssue> issues = List.of(demand(1, "NDK-006"),
                defect(2, null, "Visible defect (header, defect_checks.limitation): SCN 02.06.2024 vs deadline 31.05.2024"));
        Merged m = merge(issues, false, LIMITATION_NO_CARD);
        assertEquals(2, m.issues().size());
        assertTrue(m.issues().get(1).flags().contains(CodeDefectMerger.FLAG_CONFIRMED));
    }

    @Test
    void matcherDefectTheCodeFindsOk_isKeptAndDisputed() {
        List<MatchedIssue> issues = List.of(demand(1, "NDK-006"), defect(2, "NDK-141", "no DIN"));
        Merged m = merge(issues, false, DIN_OK);
        assertEquals(2, m.issues().size());
        assertEquals(List.of(CodeDefectMerger.FLAG_DISPUTED + ": central-tax document dated 02.05.2024 carries a DIN"), m.issues().get(1).flags());
        assertEquals("disputed", m.outcomes().get(0).action());
        // "not applicable" and cannot_test never dispute; nothing changes
        Merged na = merge(List.of(defect(1, "NDK-143", "unsigned")), false, SIGNATURE_NA,
                new CodeDefect(DefectChecker.DIN, "cannot_test", "issuing authority not stated", "NDK-141", "NDK-142"));
        assertTrue(na.outcomes().isEmpty());
        assertTrue(na.issues().get(0).flags().isEmpty());
    }

    @Test
    void renumbering_putsDefectsLast_andFollowsParentsAndTheParaMap() {
        // matcher order: defect #1, demand #2, consequential #3 on #2
        List<MatchedIssue> issues = List.of(defect(1, "NDK-143", "unsigned"), demand(2, "NDK-006"), consequential(3, "NDK-050", 2));
        Merged m = CodeDefectMerger.merge(issues, Map.of("3", List.of(2), "5", List.of(2, 3)), List.of(DIN_DEFECT), false,
                id -> true, "2019-20");

        assertEquals(List.of(1, 2, 3, 4), m.issues().stream().map(MatchedIssue::issueNo).toList());
        assertEquals("NDK-006", m.issues().get(0).primaryCardId());
        assertEquals("NDK-050", m.issues().get(1).primaryCardId());
        assertEquals(1, m.issues().get(1).parentIssueNo(), "consequential follows its parent's new number");
        assertEquals("NDK-143", m.issues().get(2).primaryCardId());
        assertEquals("NDK-141", m.issues().get(3).primaryCardId());
        assertEquals(List.of(1), m.paraMap().get("3"));
        assertEquals(List.of(1, 2), m.paraMap().get("5"));
        assertEquals(4, m.outcomes().get(0).issueNo());
    }

    @Test
    void nothingToMerge_returnsTheSameInstances() {
        List<MatchedIssue> issues = List.of(demand(1, "NDK-006"));
        Map<String, List<Integer>> paraMap = Map.of("2", List.of(1));
        Merged m = CodeDefectMerger.merge(issues, paraMap, List.of(DIN_OK, SIGNATURE_NA), false, id -> true, null);
        assertSame(issues, m.issues());
        assertSame(paraMap, m.paraMap());
        assertFalse(m.changed(issues));
    }
}
