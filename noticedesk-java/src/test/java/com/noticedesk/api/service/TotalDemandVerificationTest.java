package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.matching.DemandTotals;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.testsupport.SyntheticS74Notice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (b39) The live run on the synthetic Section 74 notice printed "Total Demand Amount: Rs 4,49,46,680" (the matcher's
 * total_demand_amount, used unchecked) while para 8 demands Rs 34,71,680 + Rs 10,23,000 = Rs 44,94,680, and
 * CHECK_FIGURE_CONSISTENCY passed. Offline: the matcher's figure is replayed; no API call.
 */
class TotalDemandVerificationTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void demandClausesOfTheNotice_sumToRs44_94_680() {
        assertEquals(0, new BigDecimal("4494680").compareTo(DemandTotals.fromDemandClauses(SyntheticS74Notice.text())));
        assertEquals(0, new BigDecimal("4494680").compareTo(DemandTotals.sumOfIssueAmounts(SyntheticS74Notice.issues())));
    }

    @Test
    void interestAndPenaltyClauses_areNotTax_andNoClausesGivesNull() {
        String t = "NOW THEREFORE you are called upon to show cause as to why:\n(a) tax of Rs. 1,00,000/- should not be demanded under Section 73;\n"
                + "(b) interest of Rs. 18,000/- should not be demanded under Section 50;\n(c) penalty of Rs. 10,000/- should not be imposed.\n"
                + "2. The noticee may reply.";
        assertEquals(0, new BigDecimal("100000").compareTo(DemandTotals.fromDemandClauses(t)));
        assertNull(DemandTotals.fromDemandClauses("A letter with Rs. 5,000 and no demand clauses."));
        assertNull(DemandTotals.fromDemandClauses(null));
    }

    @Test
    void wrongMatcherTotal_isReplacedByTheDemandClauses_withNoteAndFlag() {
        List<String> notes = new ArrayList<>();
        Set<String> flags = new LinkedHashSet<>();
        Map<String, Map<String, Object>> details = new LinkedHashMap<>();

        Double total = V7DraftingPipelineService.verifyTotalDemand(SyntheticS74Notice.LIVE_WRONG_TOTAL, SyntheticS74Notice.issues(),
                SyntheticS74Notice.text(), UUID.randomUUID(), notes, flags, details);

        assertEquals(SyntheticS74Notice.TOTAL, total);
        assertTrue(flags.contains(V7DraftingPipelineService.FLAG_TOTAL_DEMAND_CORRECTED), flags.toString());
        assertTrue(notes.stream().anyMatch(n -> n.contains("Rs 4,49,46,680") && n.contains("Rs 44,94,680")), notes.toString());
        // a correct matcher figure is kept untouched
        List<String> n2 = new ArrayList<>();
        Set<String> f2 = new LinkedHashSet<>();
        assertEquals(SyntheticS74Notice.TOTAL, V7DraftingPipelineService.verifyTotalDemand(SyntheticS74Notice.TOTAL,
                SyntheticS74Notice.issues(), SyntheticS74Notice.text(), UUID.randomUUID(), n2, f2, new LinkedHashMap<>()));
        assertTrue(f2.isEmpty() && n2.isEmpty());
    }

    @Test
    void figureCheck_failsAsBlock_whenTheStatedTotalDiffersFromIssuesAndDemandClauses() {
        MatchingResult wrong = SyntheticS74Notice.matching(SyntheticS74Notice.LIVE_WRONG_TOTAL);
        AssembledReplyResult r = assemble(wrong, "<p>PRAYER: drop it.</p>");
        assertTrue(text(r, 1).contains("Rs 4,49,46,680"), "reproduces the live defect: " + text(r, 1));

        DraftCheckResult c = figureCheck(wrong, r);
        assertFalse(c.passed(), c.details().toString());
        assertEquals("block", c.severity());
        String mismatches = c.details().get("mismatches").toString();
        assertTrue(mismatches.contains("sum of the issue amounts Rs 44,94,680"), mismatches);
        assertTrue(mismatches.contains("demand clauses Rs 44,94,680"), mismatches);
    }

    @Test
    void correctedTotal_isStatedEverywhere_andTheFigureCheckPasses() {
        MatchingResult right = SyntheticS74Notice.matching(SyntheticS74Notice.TOTAL);
        AssembledReplyResult r = assemble(right, "<p>PRAYER: DROP the proposed demand of Rs. {{issue.amount_tax}}.</p>");
        String all = String.join("\n", r.sections().stream().map(AssembledSection::bodyHtml).toList());
        assertTrue(all.contains("Rs 44,94,680"), all);
        assertFalse(all.contains("4,49,46,680"));
        DraftCheckResult c = figureCheck(right, r);
        assertTrue(c.passed(), c.details().toString());
    }

    @Test
    void prayerAmountTax_isFilledFromTheVerifiedTotal() {
        AssembledReplyResult r = assemble(SyntheticS74Notice.matching(SyntheticS74Notice.TOTAL),
                "<p>PRAYER: DROP the proposed demand of Rs. {{issue.amount_tax}}.</p>");
        String prayer = text(r, 12);
        assertTrue(prayer.contains("DROP the proposed demand of Rs 44,94,680."), prayer);
        assertFalse(prayer.contains("MISSING"), prayer);
        // the wrong matcher figure is never put in the prayer: the notice's demand clauses are the verified total
        String fromWrong = text(assemble(SyntheticS74Notice.matching(SyntheticS74Notice.LIVE_WRONG_TOTAL),
                "<p>PRAYER: DROP the proposed demand of Rs. {{issue.amount_tax}}.</p>"), 12);
        assertTrue(fromWrong.contains("Rs 44,94,680"), fromWrong);
    }

    // ------------------------------------------------------------------ helpers

    private AssembledReplyResult assemble(MatchingResult mr, String prayer) {
        ReplyAssemblyService svc = new ReplyAssemblyService(null, null, om);
        Map<String, String> sections = new TreeMap<>(Map.of("08", "<p>Cross-examination requested.</p>", "12", prayer,
                "14", "<p>CLIENT SUMMARY: {{notice.type}}.</p>", "15", "<p>FILING CHECKLIST: DRC-06.</p>"));
        svc.registerInMemoryStageTemplate(new StageTemplate("scn_74", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                sections, null, null, null));
        return svc.assembleReply(mr, List.of(), SyntheticS74Notice.input(), "scn_74");
    }

    private static DraftCheckResult figureCheck(MatchingResult mr, AssembledReplyResult r) {
        return new DraftCheckService(null).runAllChecks(mr, r, SyntheticS74Notice.SECTION, "scn_74", SyntheticS74Notice.text()).stream()
                .filter(c -> "CHECK_FIGURE_CONSISTENCY".equals(c.checkName())).findFirst().orElseThrow();
    }

    private static String text(AssembledReplyResult r, int num) {
        return r.sections().stream().filter(s -> s.num() == num).map(AssembledSection::bodyHtml).findFirst().orElse("");
    }
}
