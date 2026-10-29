package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** CHECK_NO_UNRESOLVED_PLACEHOLDERS and CHECK_PROCEEDING_CONSISTENT. */
class DraftCheckPlaceholderTest {

    private final DraftCheckService service = new DraftCheckService(null);

    private DraftCheckResult run(int sectionNum, String body, String noticeSection, String stage, String checkName) {
        List<AssembledSection> sections = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            sections.add(new AssembledSection(i, "S" + i, i == sectionNum ? body : "<p>Clean text.</p>"));
        }
        MatchingResult mr = new MatchingResult(new MatchedNoticeInfo(null, null, null, null, null, null, null, null),
                List.of(), Map.of(), List.of(), List.of(), "h", 0, 0, 0, 0);
        return service.runAllChecks(mr, new AssembledReplyResult(sections, List.of(), List.of(), List.of(), List.of()), noticeSection, stage)
                .stream().filter(c -> c.checkName().equals(checkName)).findFirst().orElseThrow();
    }

    private DraftCheckResult placeholders(int sectionNum, String body) {
        return run(sectionNum, body, null, null, "CHECK_NO_UNRESOLVED_PLACEHOLDERS");
    }

    @Test
    void cleanDraftPasses() {
        assertTrue(placeholders(1, "<p>The demand is null and void. [[MISSING: date_of_receipt]] [[PARTNER: x]] [[PENDING_AI: Issue 1]]</p>").passed());
    }

    @Test
    void flagsRefNullStrayMarkersAndInternalText() {
        assertFalse(placeholders(5, "<li>Denied, see para [ref].</li>").passed());
        assertFalse(placeholders(1, "<p>Reply to notice null dated 01-01-2024.</p>").passed());
        assertFalse(placeholders(4, "<p>[[TODO fill this]]</p>").passed());
        assertFalse(placeholders(1, "<li>Issue #1: No template available for card null</li>").passed());
        assertFalse(placeholders(4, "<p>{{issue.amount}}</p>").passed());
        assertFalse(placeholders(4, "<p>As per CARD-006 the claim fails.</p>").passed());
        assertFalse(placeholders(4, "<p>[STUB - no model call] text</p>").passed());
        assertFalse(placeholders(6, "<p>[Sonnet AI] Precedent text.</p>").passed());
    }

    @Test
    void internalTextInSection13IsAllowed() {
        assertTrue(placeholders(13, "<li>Issue #1: Cards=[CARD-006], Note=No template available for card null</li>").passed());
    }

    @Test
    void proceedingConsistency() {
        assertTrue(run(2, "<p>issued under Section 171 of the CGST Act</p>", "Section 171", "anti_profiteering", "CHECK_PROCEEDING_CONSISTENT").passed());
        assertFalse(run(12, "<p>drop the Show Cause Notice issued under Section 73</p>", "Section 171", "anti_profiteering", "CHECK_PROCEEDING_CONSISTENT").passed());
        assertFalse(run(2, "<p>under Section 171</p>", "Section 171", "scn_73", "CHECK_PROCEEDING_CONSISTENT").passed());
        assertTrue(run(12, "<p>under Section 73 and Section 75(4)</p>", "Section 73", "scn_73", "CHECK_PROCEEDING_CONSISTENT").passed());
    }
}
