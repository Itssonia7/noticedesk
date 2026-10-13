package com.noticedesk.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offline: the card-level (primary card only) scoring of the matching eval runner (no model, no database). */
class MatchingEvalCardScoreTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static JsonNode key(String json) throws Exception {
        return OM.readTree(json);
    }

    /** A returned issue with a primary card (or none) and no alternates. */
    private static MatchedIssue issue(String status, String why, String... primary) {
        return new MatchedIssue(1, status, List.of(primary), why, List.of(), Map.of(), List.of(), List.of());
    }

    /** A returned issue with a primary card and alternates. */
    private static MatchedIssue issueAlt(String status, String primary, String... alternates) {
        return new MatchedIssue(1, status, List.of(primary), List.of(alternates), "w", List.of(), Map.of(), List.of(), List.of());
    }

    @Test
    void correctNeedsStatusAndThePrimaryAmongTheExpectedCards() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 2, "issues": [
                  {"issue_no": 1, "status": "full", "card_ids": ["NDK-006", "NDK-007"]},
                  {"issue_no": 2, "status": "partial", "card_ids": ["NDK-011"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("n1", key,
                List.of(issue("full", "a", "NDK-007"), issueAlt("partial", "NDK-011", "NDK-012")), false);
        assertEquals(2, score.expected());
        assertEquals(2, score.correct());
        assertEquals(0, score.primaryRightStatusWrong());
        assertEquals(0, score.rightCardInAlternates());
        assertEquals(0, score.wrongCard());
        assertEquals(0, score.missed());
        assertEquals(0, score.extra());
        assertTrue(score.allCorrect());
        assertTrue(score.wrong().isEmpty());
    }

    @Test
    void shortlistCountsForNothing_rightCardOnlyInAlternates() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("n2", key, List.of(issueAlt("full", "NDK-099", "NDK-006")), false);
        assertEquals(0, score.correct());
        assertEquals(1, score.rightCardInAlternates());
        assertEquals(0, score.wrongCard());
        assertEquals(0, score.missed());
        assertFalse(score.allCorrect());
        assertEquals(1, score.wrong().size());
        assertEquals("full [NDK-006] [right card only in alternates]", score.wrong().get(0).expected());
        assertEquals("demand:full[NDK-099] alt[NDK-006]", score.wrong().get(0).got());
    }

    @Test
    void wrongPrimary_isWrong_evenWithTheSameStatus() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("n2", key, List.of(issue("full", "model text", "NDK-099")), false);
        assertEquals(0, score.correct());
        assertEquals(1, score.wrongCard());
        assertEquals(0, score.missed());
        assertFalse(score.allCorrect());
        assertEquals(1, score.wrong().size());
        assertEquals("full [NDK-006] [wrong]", score.wrong().get(0).expected());
        assertEquals("demand:full[NDK-099]", score.wrong().get(0).got());
        assertEquals("model text", score.wrong().get(0).why());
    }

    @Test
    void primaryRightStatusWrong_isCountedSeparately() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]}]}
                """);
        var score = MatchingEvalRunnerTest.scoreCards("n3", key, List.of(issue("partial", "x", "NDK-006")), false);
        assertEquals(0, score.correct());
        assertEquals(1, score.primaryRightStatusWrong());
        assertEquals(0, score.missed());
        assertEquals("full [NDK-006] [primary card right, status wrong]", score.wrong().get(0).expected());
        assertEquals("demand:partial[NDK-006]", score.wrong().get(0).got());
    }

    @Test
    void nothingReturned_isMissed() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 1, "issues": [{"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]}]}
                """);
        var nothing = MatchingEvalRunnerTest.scoreCards("n3", key, List.of(), false);
        assertEquals(1, nothing.missed());
        assertEquals(0, nothing.wrongCard());
        assertEquals("full [NDK-006] [missed]", nothing.wrong().get(0).expected());
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
        assertEquals(1, score.wrongCard());
        assertEquals(0, score.missed());
    }

    @Test
    void expectedIssuesAreDedupedByCardSet_beforePairing() throws Exception {
        // the key lists the Section 50 interest as its own issue on the main issue's card: one ground
        JsonNode key = key("""
                {"expected_issues": 3, "issues": [
                  {"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]},
                  {"issue_no": 2, "status": "full", "card_ids": ["ndk-006"]},
                  {"issue_no": 3, "status": "full", "card_ids": ["NDK-020"]}]}
                """);
        assertEquals(2, MatchingEvalRunnerTest.dedupeExpected(key.path("issues")).size());

        var score = MatchingEvalRunnerTest.scoreCards("n6", key,
                List.of(issue("full", "a", "NDK-006"), issue("full", "b", "NDK-020")), false);
        assertEquals(2, score.expected());
        assertEquals(2, score.correct());
        assertEquals(0, score.extra(), "one issue per ground is not extra against the deduped key");
        assertTrue(score.allCorrect());

        // a third returned issue is extra against the deduped count
        var three = MatchingEvalRunnerTest.scoreCards("n6", key,
                List.of(issue("full", "a", "NDK-006"), issue("full", "b", "NDK-020"), issue("full", "c", "NDK-006")), false);
        assertEquals(1, three.extra());
        assertFalse(three.allCorrect());
    }

    @Test
    void expectedNoneIssues_areNeverDeduped() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 2, "issues": [
                  {"issue_no": 1, "status": "none", "card_ids": []},
                  {"issue_no": 2, "status": "none", "card_ids": []}]}
                """);
        assertEquals(2, MatchingEvalRunnerTest.dedupeExpected(key.path("issues")).size());
        var score = MatchingEvalRunnerTest.scoreCards("n6b", key, List.of(issue("none", "a"), issue("none", "b")), false);
        assertEquals(2, score.correct());
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
                List.of(new MatchedIssue(1, "full", List.of("NDK-099"), List.of("NDK-100"),
                        "the model's explanation of the notice", List.of(), Map.of(), List.of(), List.of())), true);
        var wm = score.wrong().get(0);
        assertEquals("notice_real_04D", wm.noticeId());
        assertTrue(wm.expected().contains("NDK-006"), wm.expected());
        assertEquals("demand:full[NDK-099] alt[NDK-100]", wm.got());
        assertEquals("(withheld: private notice)", wm.why());
        assertFalse(wm.toString().contains("explanation"));
    }

    @Test
    void privateNotice_withShowWhy_reportsWhyAndNotThisIf_withNoticeIdAndIssueNo() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 2, "issues": [
                  {"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]},
                  {"issue_no": 2, "status": "full", "card_ids": ["NDK-141"]}]}
                """);
        MatchedIssue demand = new MatchedIssue(1, "full", "demand", List.of("NDK-099"), List.of("NDK-100"),
                "order service date: 21.07.2023 vs before 01.10.2023 -> satisfied",
                List.of("NDK-099: Not this if X (use NDK-100) - not triggered"), Map.of(), List.of("3"), List.of());
        MatchedIssue defect = new MatchedIssue(2, "none", "defect", List.of(), List.of(), "no card for the missing DIN",
                List.of(), Map.of(), List.of("header"), List.of("defect_without_card"));
        var score = MatchingEvalRunnerTest.scoreCards("notice_syn_07", key, List.of(demand, defect), true, true);

        assertEquals(2, score.wrong().size());
        var first = score.wrong().get(0);
        assertEquals("demand:full[NDK-099] alt[NDK-100], defect:none[]", first.got());
        assertEquals("issue 1 why: order service date: 21.07.2023 vs before 01.10.2023 -> satisfied"
                + " | not_this_if: NDK-099: Not this if X (use NDK-100) - not triggered", first.why());
        var second = score.wrong().get(1);
        assertEquals("issue 2 why: no card for the missing DIN | not_this_if: (none)", second.why());

        // the same issues without the flag: withheld as before
        var hidden = MatchingEvalRunnerTest.scoreCards("notice_syn_07", key, List.of(demand, defect), true, false);
        assertEquals("(withheld: private notice)", hidden.wrong().get(0).why());
        assertEquals("(withheld: private notice)", hidden.wrong().get(1).why());

        // console lines: one why line and one not_this_if line per issue, prefixed with notice id and issue no
        List<String> lines = MatchingEvalRunnerTest.reasonLines("notice_syn_07", List.of(demand, defect));
        assertEquals(List.of(
                "  notice_syn_07 issue 1 [demand:full[NDK-099] alt[NDK-100]] why: order service date: 21.07.2023 vs before 01.10.2023 -> satisfied",
                "  notice_syn_07 issue 1 [demand:full[NDK-099] alt[NDK-100]] not_this_if: NDK-099: Not this if X (use NDK-100) - not triggered",
                "  notice_syn_07 issue 2 [defect:none[]] why: no card for the missing DIN",
                "  notice_syn_07 issue 2 [defect:none[]] not_this_if: (none)"), lines);
    }

    @Test
    void defectCounts_returnedByKind_expectedByDefectCardList() throws Exception {
        JsonNode key = key("""
                {"expected_issues": 3, "issues": [
                  {"issue_no": 1, "status": "full", "card_ids": ["NDK-006"]},
                  {"issue_no": 2, "status": "full", "card_ids": ["ndk-141"]},
                  {"issue_no": 3, "status": "full", "card_ids": ["NDK-141"]},
                  {"issue_no": 4, "status": "partial", "card_ids": ["NDK-104", "NDK-105"]}]}
                """);
        List<MatchedIssue> returned = List.of(
                new MatchedIssue(1, "full", "demand", List.of("NDK-006"), List.of(), "w", List.of(), Map.of(), List.of(), List.of()),
                new MatchedIssue(2, "full", "defect", List.of("NDK-141"), List.of(), "w", List.of(), Map.of(), List.of(), List.of()),
                new MatchedIssue(3, "none", "defect", List.of(), List.of(), "w", List.of(), Map.of(), List.of(), List.of()));
        assertEquals(2, MatchingEvalRunnerTest.defectIssuesReturned(returned));
        assertEquals(0, MatchingEvalRunnerTest.defectIssuesReturned(List.of()));

        // de-duplicated expected issues (NDK-141 twice = one) whose card is in the list; ids compared upper-cased
        assertEquals(2, MatchingEvalRunnerTest.defectIssuesExpected(key, java.util.Set.of("NDK-141", "NDK-105")));
        assertEquals(0, MatchingEvalRunnerTest.defectIssuesExpected(key, java.util.Set.of("NDK-999")));
        assertEquals(-1, MatchingEvalRunnerTest.defectIssuesExpected(key, null), "no defect card list: skipped");
        assertEquals(0, MatchingEvalRunnerTest.defectIssuesExpected(key("{\"para_count\": 1}"), java.util.Set.of("NDK-141")));
    }

    @Test
    void answerKeyWithoutIssues_scoresNothing() throws Exception {
        var score = MatchingEvalRunnerTest.scoreCards("n7", key("{\"para_count\": 3}"), List.of(issue("full", "a", "NDK-001")), false);
        assertEquals(0, score.expected());
        assertEquals(0, score.extra());
        assertTrue(score.wrong().isEmpty());
    }
}
