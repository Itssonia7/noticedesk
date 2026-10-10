package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.SourceMapEntry;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DraftCheckServiceTest {

    private DraftCheckService checkService;

    @BeforeEach
    void setUp() {
        checkService = new DraftCheckService(null);
    }

    @Test
    void testAll7ChecksPassOnCleanDraft() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN1", "DIN1", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchedIssue issue1 = new MatchedIssue(1, "full", List.of("CARD-006"), "Why", List.of(), Map.of("period", "2022-23", "amount", 100000.0), List.of(), List.of());
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(issue1), Map.of("record", List.of(1)), List.of(), List.of(), "hash", 10, 10, 0, 0);

        List<AssembledSection> sections = new ArrayList<>();
        List<String> titles = List.of(
                "01 Executive Summary", "02 Notice Understanding", "03 Factual Background",
                "04 Issue-wise Response", "05 Para-wise Reply", "06 Legal Submissions",
                "07 Procedural Objections", "08 Cross-Examination Request",
                "09 Related Context from Other Registrations", "10 Documents Enclosed",
                "11 Annexure Index", "12 Prayer", "13 Internal Partner Note",
                "14 Client Summary", "15 Filing Checklist"
        );

        for (int i = 0; i < 15; i++) {
            String body = "<p>Text for section " + (i + 1) + ". Issue #1 grounds with amount Rs 1,00,000.</p>";
            sections.add(new AssembledSection(i + 1, titles.get(i), body));
        }

        AssembledReplyResult assemblyResult = new AssembledReplyResult(
                sections,
                List.of(new SourceMapEntry(1, 4, "TPL-006", 1, "BLK-1")),
                List.of(), List.of(), List.of()
        );

        List<DraftCheckResult> results = checkService.runAllChecks(matchingResult, assemblyResult);

        assertEquals(7, results.size());
        for (DraftCheckResult cr : results) {
            assertTrue(cr.passed(), "Check " + cr.checkName() + " should pass");
        }
    }

    @Test
    void testCheckMarkersListFailsWhenMissingOrPendingAiPresent() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN1", "DIN1", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(), Map.of(), List.of(), List.of(), "hash", 10, 10, 0, 0);

        List<AssembledSection> sections = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            String body = (i == 4) ? "<p>[[PENDING_AI: Issue 1]] and [[MISSING: client_name]]</p>" : "<p>Section " + i + "</p>";
            sections.add(new AssembledSection(i, "Section " + i, body));
        }

        AssembledReplyResult assemblyResult = new AssembledReplyResult(sections, List.of(), List.of(), List.of(), List.of());

        List<DraftCheckResult> results = checkService.runAllChecks(matchingResult, assemblyResult);

        DraftCheckResult markersCheck = results.stream().filter(c -> "CHECK_MARKERS_LIST".equals(c.checkName())).findFirst().orElseThrow();
        assertFalse(markersCheck.passed());
        assertEquals("block", markersCheck.severity());
    }

    @Test
    void testCheckNoUnresolvedPlaceholdersFailsWhenCurlyBracesRemain() {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("DIN1", "DIN1", "2023-10-15", "30 Days", "2022-23", "2022-23", 100000.0, "Tax Officer");
        MatchingResult matchingResult = new MatchingResult(noticeInfo, List.of(), Map.of(), List.of(), List.of(), "hash", 10, 10, 0, 0);

        List<AssembledSection> sections = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            String body = (i == 4) ? "<p>Leftover {{unresolved.var}} token</p>" : "<p>Section " + i + "</p>";
            sections.add(new AssembledSection(i, "Section " + i, body));
        }

        AssembledReplyResult assemblyResult = new AssembledReplyResult(sections, List.of(), List.of(), List.of(), List.of());

        List<DraftCheckResult> results = checkService.runAllChecks(matchingResult, assemblyResult);

        DraftCheckResult curlyCheck = results.stream().filter(c -> "CHECK_NO_UNRESOLVED_PLACEHOLDERS".equals(c.checkName())).findFirst().orElseThrow();
        assertFalse(curlyCheck.passed());
        assertEquals("block", curlyCheck.severity());
    }
}

