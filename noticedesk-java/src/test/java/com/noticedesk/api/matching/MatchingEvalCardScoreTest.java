package com.noticedesk.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offline: the card-level scoring of the matching eval runner (no model, no database). */
class MatchingEvalCardScoreTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static JsonNode key(String json) throws Exception {
        return OM.readTree(json);
    }

    private static MatchedIssue issue(String status, String why, String... cards) {
        return new MatchedIssue(1, status, List.of(cards), why, List.of(), Map.of(), List.of(), List.of());
    }

    @Test
    void correctNeedsStatusAndOneOfTheExpectedCards() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 2, "issues": [
                  {"issue_no": 1, "status": "full", "card_ids": ["NDK-006", "NDK-007"]},
                  {"issue_no": 2, "status": "partial", "card_ids": ["NDK-011"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("n1", key,
                List.of(issue("full", "a", "NDK-007"), issue("partial", "b", "NDK-011", "NDK-012")), false);
        assertEquals(2, score.expected());
        assertEquals(2, score.correct());
        assertEquals(0, score.statusRightWrongCard());
        assertEquals(0, score.missed());
        assertEquals(0, score.extra());
        assertTrue(score.allCorrect());
        assertTrue(score.wrong().isEmpty());
    }

    @Test
    void statusRightWrongCard_isNotCorrect() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("n2", key, List.of(issue("full", "model text", "NDK-099")), false);
        assertEquals(0, score.correct());
        assertEquals(1, score.statusRightWrongCard());
        assertEquals(0, score.missed());
        assertFalse(score.allCorrect());
        assertEquals(1, score.wrong().size());
        assertEquals("full [NDK-006] [status right, wrong card]", score.wrong().get(0).expected());
        assertEquals("full[NDK-099]", score.wrong().get(0).got());
        assertEquals("model text", score.wrong().get(0).why());
    }

    @Test
    void wrongStatus_orNothingReturned_isMissed() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]}]}
                """);
        var wrongStatus = MatchingEvalRunnerTest.scoreCards("n3", key, List.of(issue("partial", "x", "NDK-006")), false);
        assertEquals(1, wrongStatus.missed());
        assertEquals(0, wrongStatus.statusRightWrongCard());

        var nothing = MatchingEvalRunnerTest.scoreCards("n3", key, List.of(), false);
        assertEquals(1, nothing.missed());
        assertEquals("no issue returned", nothing.wrong().get(0).got());
        assertEquals("No issue returned", nothing.wrong().get(0).why());
    }

    @Test
    void noneNeedsNoCard_andEachReturnedIssueCountsOnce() throws Exception {
        JsonNode none = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "none", "card_ids": []}]}
                """);
        assertEquals(1, MatchingEvalRunnerTest.scoreCards("n4", none, List.of(issue("none", "x")), false).correct());

        // two expected full issues on different cards; the model returned the same card twice
        JsonNode two = key("""
                {"expected_issues": 2, "issues": [
                  {"issue_no": 1, "status": "full", "card_ids": ["NDK-001"]},
                  {"issue_no": 2, "status": "full", "card_ids": ["NDK-002"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("n4", two,
                List.of(issue("full", "a", "NDK-001"), issue("full", "b", "NDK-001")), false);
        assertEquals(1, score.correct());
        assertEquals(1, score.statusRightWrongCard());
        assertEquals(0, score.missed());
    }

    @Test
    void extraReturnedIssues_countedAndBlockAllCorrect() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("n5", key,
                List.of(issue("full", "a", "NDK-006"), issue("partial", "b", "NDK-020")), false);
        assertEquals(1, score.correct());
        assertEquals(1, score.extra());
        assertFalse(score.allCorrect());
        assertEquals(1, score.wrong().size());
        assertTrue(score.wrong().get(0).expected().contains("extra returned: 1"), score.wrong().get(0).expected());
    }

    @Test
    void privateNotice_keepsStatusesAndCardIds_withholdsWhy() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("notice_real_04D", key,
                List.of(issue("full", "the model's explanation of the notice", "NDK-099")), true);
        var wm = score.wrong().get(0);
        assertEquals("notice_real_04D", wm.noticeId());
        assertTrue(wm.expected().contains("NDK-006"), wm.expected());
        assertEquals("full[NDK-099]", wm.got());
        assertEquals("(withheld: private notice)", wm.why());
        assertFalse(wm.toString().contains("explanation"));
    }

    @Test
    void answerKeyWithoutIssues_scoresNothing() throws Exception {
        var score = MatchingEvalRunnerTest.scoreCards("n7", key("{\"para_count\": 3}"), List.of(issue("full", "a", "NDK-001")), false);
        assertEquals(0, score.expected());
        assertEquals(0, score.extra());
        assertTrue(score.wrong().isEmpty());
    }
}
