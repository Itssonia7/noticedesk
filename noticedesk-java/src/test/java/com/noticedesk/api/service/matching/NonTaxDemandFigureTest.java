package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.layout.ReplyLayout;
import com.noticedesk.api.service.layout.ReplyLayout.Table;
import com.noticedesk.api.service.matching.DemandTotals.AmountKind;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.testsupport.SyntheticS73Notice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live run at c82df8b on the synthetic Section 73 audit notice: CHECK_FIGURE_CONSISTENCY failed (block) with "Total
 * demand Rs 11,09,570 differs from the sum of the issue amounts Rs 11,68,630": the Objection 5 issue's Rs 59,060 is late
 * fee Rs 17,800 (Section 47) + interest Rs 41,260 (Section 50), not tax, and was added to the tax issues. Offline replay.
 */
class NonTaxDemandFigureTest {

    private static final String TEXT = SyntheticS73Notice.text();

    @Test
    void lateFeeAndInterestIssue_isSplitFromItsParagraph_notCountedAsTax() {
        MatchedIssue obj5 = SyntheticS73Notice.issues().get(4);
        Map<AmountKind, BigDecimal> c = DemandTotals.components(obj5, TEXT);
        assertEquals(0, new BigDecimal("17800").compareTo(c.get(AmountKind.LATE_FEE)), c.toString());
        assertEquals(0, new BigDecimal("41260").compareTo(c.get(AmountKind.INTEREST)), c.toString());
        assertNull(c.get(AmountKind.TAX));
        // the same split without a legal section (the paragraph's wording decides)
        MatchedIssue noSection = SyntheticS73Notice.issue(5, "demand", null, "59060", List.of("6"), null);
        assertNull(DemandTotals.components(noSection, TEXT).get(AmountKind.TAX));
        // a tax issue stays tax, even when its paragraph mentions interest
        MatchedIssue obj6 = SyntheticS73Notice.issues().get(6);
        assertEquals(Set.of(AmountKind.TAX), DemandTotals.components(obj6, TEXT).keySet());

        assertEquals(0, new BigDecimal("1109570").compareTo(DemandTotals.sumOfIssueAmounts(SyntheticS73Notice.issues(), TEXT)));
        assertEquals(0, new BigDecimal("1109570").compareTo(DemandTotals.fromDemandClauses(TEXT)));
    }

    @Test
    void demandClauses_lateFeeAndPenalties_areReadWithTheirSections_interestUnquantified() {
        List<DemandTotals.StatedFigure> f = DemandTotals.nonTaxDemandClauses(TEXT);
        assertEquals(3, f.size(), f.toString());
        assertEquals(new DemandTotals.StatedFigure(AmountKind.LATE_FEE, new BigDecimal("17800"), "Section 47"), f.get(0));
        assertEquals(new DemandTotals.StatedFigure(AmountKind.PENALTY, new BigDecimal("110957"), "Section 73(9)"), f.get(1));
        assertEquals(new DemandTotals.StatedFigure(AmountKind.PENALTY, new BigDecimal("150000"), "Section 125"), f.get(2));
    }

    @Test
    void figureCheck_passesOnTheLiveCase() {
        DraftCheckResult c = figureCheck(SyntheticS73Notice.issues(), SyntheticS73Notice.TAX_TOTAL);
        assertTrue(c.passed(), c.details().toString());
        assertEquals("Rs 11,09,570", c.details().get("issue_sum"));
    }

    @Test
    void realTaxMismatch_stillBlocks_andALateFeeMismatchIsCaughtSeparately() {
        List<MatchedIssue> tenTimes = new ArrayList<>(SyntheticS73Notice.issues());
        tenTimes.set(0, SyntheticS73Notice.issue(1, "demand", "Section 16(2)(aa)", "4445200", List.of("2"), null));
        DraftCheckResult c = figureCheck(tenTimes, SyntheticS73Notice.TAX_TOTAL);
        assertFalse(c.passed());
        assertEquals("block", c.severity());
        assertTrue(c.details().get("mismatches").toString().contains("sum of the issue amounts"), c.details().toString());

        List<MatchedIssue> wrongFee = new ArrayList<>(SyntheticS73Notice.issues());
        wrongFee.set(4, SyntheticS73Notice.issue(5, "demand", "Section 47", "178000", List.of("6"), null));
        DraftCheckResult f = figureCheck(wrongFee, SyntheticS73Notice.TAX_TOTAL);
        assertFalse(f.passed());
        String m = f.details().get("mismatches").toString();
        assertTrue(m.contains("Late fee Rs 1,78,000") && m.contains("late fee clause Rs 17,800"), m);
        assertFalse(m.contains("sum of the issue amounts"), "the tax total itself is right: " + m);
    }

    @Test
    void summaryTable_showsTotalTaxDemand_thenLateFeeAndPenaltiesOnSeparateRows() {
        AssembledReplyResult r = assemble(SyntheticS73Notice.issues());
        ReplyLayout l = SyntheticS73Notice.layout(r);
        Table t = l.reply().stream().filter(b -> b instanceof Table x && x.header().contains("Ground")).map(b -> (Table) b)
                .findFirst().orElseThrow();
        List<String> labels = t.rows().stream().map(row -> ReplyLayout.plain(row.get(1)) + " = " + ReplyLayout.plain(row.get(2))).toList();
        int total = labels.indexOf("Total tax demand = Rs 11,09,570");
        assertTrue(total >= 0, labels.toString());
        assertEquals(List.of("Late fee under Section 47 = Rs 17,800", "Penalty proposed under Section 73(9) = Rs 1,10,957",
                "Penalty proposed under Section 125 = Rs 1,50,000"), labels.subList(total + 1, labels.size()));
        assertFalse(labels.stream().anyMatch(s -> s.startsWith("Interest")), "interest is not quantified in the demand clauses");
        assertFalse(labels.stream().anyMatch(s -> s.contains("11,68,630")));
    }

    // ------------------------------------------------------------------ helpers

    private static AssembledReplyResult assemble(List<MatchedIssue> issues) {
        MatchingResult mr = SyntheticS73Notice.matching(issues, SyntheticS73Notice.TAX_TOTAL);
        return SyntheticS73Notice.assemble(mr, SyntheticS73Notice.aiFor(issues), List.of());
    }

    private static DraftCheckResult figureCheck(List<MatchedIssue> issues, double total) {
        MatchingResult mr = SyntheticS73Notice.matching(issues, total);
        AssembledReplyResult r = SyntheticS73Notice.assemble(mr, SyntheticS73Notice.aiFor(issues), List.of());
        return new DraftCheckService(null).runAllChecks(mr, r, SyntheticS73Notice.SECTION, "scn_73", TEXT).stream()
                .filter(c -> "CHECK_FIGURE_CONSISTENCY".equals(c.checkName())).findFirst().orElseThrow();
    }
}
