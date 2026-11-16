package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import com.noticedesk.api.testsupport.SyntheticS74Notice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * (b42) CHECK_PROCEEDING_CONSISTENT failed with "section 12 refers to Section 73 but the notice is under Section 74"
 * because the Section 74 prayer rightly says "HOLD that Section 74 is not invocable ... and that the matter, if at all,
 * falls under Section 73". The standard 74 -> 73 fallback does not count; a Section 73 SCN prayer still fails.
 */
class ProceedingFallbackCheckTest {

    private static DraftCheckResult check(String prayer, String noticeSection, String stage) {
        List<AssembledSection> sections = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            sections.add(new AssembledSection(i, "S" + i, i == 12 ? prayer : "<p>Reply to the notice under " + noticeSection + ".</p>"));
        }
        MatchingResult mr = SyntheticS74Notice.matching(SyntheticS74Notice.TOTAL);
        return new DraftCheckService(null).runAllChecks(mr, new AssembledReplyResult(sections, List.of(), List.of(), List.of(), List.of()),
                noticeSection, stage).stream().filter(c -> "CHECK_PROCEEDING_CONSISTENT".equals(c.checkName())).findFirst().orElseThrow();
    }

    @Test
    void section74Prayer_withTheStandardFallbackTo73_passes() {
        String livePrayer = "<p>(a) HOLD that Section 74 is not invocable as no fraud, wilful misstatement or suppression of facts is"
                + " established, and that the matter, if at all, falls under Section 73;</p><p>(b) DROP the proposed demand.</p>";
        DraftCheckResult c = check(livePrayer, "Section 74(1)", "scn_74");
        assertTrue(c.passed(), c.details().toString());

        for (String phrase : new String[] {
                "the demand, if at all, could be raised only under Section 73(1) of the CGST Act, 2017",
                "the proceedings lie, if at all, under Section 73",
                "the notice ought to have been issued under Section 73 and not Section 74",
                "the department could at best invoke Section 73",
                "the demand is to be re-determined under Section 73 in terms of Section 75(2)",
                "the proceeding is not under Section 74 but under Section 73"}) {
            DraftCheckResult r = check("<p>PRAYER: " + phrase + ".</p>", "Section 74", "scn_74");
            assertTrue(r.passed(), phrase + " -> " + r.details());
        }
    }

    @Test
    void section73TemplateUsedForASection74Notice_stillFails() {
        String scn73Prayer = "<p>PRAYER: It is humbly prayed that the proceedings initiated by notice REF dated 29-11-2024 under"
                + " Section 73 be dropped in full and no penalty or interest be levied.</p>";
        DraftCheckResult c = check(scn73Prayer, "Section 74(1)", "scn_74");
        assertFalse(c.passed());
        assertTrue(c.details().get("problems").toString().contains("section 12 refers to Section 73"), c.details().toString());
        // the stage key itself still fails as before
        DraftCheckResult stage = check("<p>PRAYER: drop it.</p>", "Section 74(1)", "scn_73");
        assertFalse(stage.passed(), stage.details().toString());
        // the fallback text in a prayer that also names a Section 73 proceeding does not hide that mention
        DraftCheckResult mixed = check("<p>PRAYER: drop the notice issued under Section 73; the matter, if at all, falls under Section 73.</p>",
                "Section 74", "scn_74");
        assertFalse(mixed.passed(), mixed.details().toString());
    }

    @Test
    void section73Notice_isUnaffected() {
        assertTrue(check("<p>PRAYER: drop the proceedings under Section 73.</p>", "Section 73", "scn_73").passed());
        assertFalse(check("<p>PRAYER: drop the proceedings under Section 74.</p>", "Section 73", "scn_73").passed());
    }
}
