package com.noticedesk.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rules of the live matching prompt (prompts/issue_matching_v2.md: grounds-first splitting, one primary card
 * plus alternates, decisive-fact status) and the testbench answer keys that follow the splitting rules
 * (live runs split notices 001 and 010 differently from run to run). No model calls.
 */
class MatchingPromptRulesTest {

    private static final String PROMPT = "/prompts/" + com.noticedesk.api.agent.IssueMatchingAgent.PROMPT_VERSION + ".md";

    /** Interest / penalty provisions that are consequential to a main demand unless an exception applies. */
    private static final Pattern INTEREST_OR_PENALTY = Pattern.compile("(?i)Section\\s*(50|73\\(9\\)|74\\(9\\)|122|125|129|171\\(3A\\))\\b");

    /**
     * Answer-key issues that are interest / penalty issues on purpose: notice 04's ONLY demand is interest;
     * notice 06 Ground II challenges interest on an independent ground (late return, computed on gross liability).
     */
    private static final Map<String, Integer> SEPARATE_INTEREST_ISSUES = Map.of(
            "notice_04_fullmatch_sec50.json", 1,
            "notice_06_hybrid_sec73_sec50.json", 2);

    @Test
    void agentLoadsTheV2Prompt_andV1StaysUntouched() throws IOException {
        assertEquals("issue_matching_v2", com.noticedesk.api.agent.IssueMatchingAgent.PROMPT_VERSION);
        String v1 = resource("/prompts/issue_matching_v1.md");
        assertTrue(v1.contains("Assign up to 2-3 `card_ids` per issue"));
        assertFalse(v1.contains("alternates"));
    }

    @Test
    void promptWorksGroundsFirst_withOnePrimaryCardAndAlternates() throws IOException {
        String prompt = resource(PROMPT);
        // 1(a) grounds first, one issue per ground
        assertTrue(prompt.contains("`issues.length` MUST equal `grounds.length`"));
        assertTrue(prompt.contains("Never merge two grounds into one issue because they share a card, a section or a period"));
        assertTrue(prompt.contains("Never split one ground into several issues by tax head"));
        // 1(b) one primary + up to 2 alternates, sibling rule
        assertTrue(prompt.contains("`card_id` = the ONE best card"));
        assertTrue(prompt.contains("`alternates` = up to 2 other plausible card ids"));
        assertTrue(prompt.contains("Never put the primary card in `alternates`"));
        assertTrue(prompt.contains("the primary MUST be that sibling card (NDK-xxx)"));
        assertTrue(prompt.contains("DECISIVE"));
        // 1(c) status rules and the hedging rule
        assertTrue(prompt.contains("DECISIVE facts are ALL present in the notice"));
        assertTrue(prompt.contains("The match is still `\"full\"` when amounts or other non-decisive details are missing"));
        assertTrue(prompt.contains("at least one DECISIVE fact is absent from or contradicted by the notice"));
        assertTrue(prompt.contains("Name the absent, contradicted or extra fact in `why`"));
        assertTrue(prompt.contains("Being unsure between two sibling cards is NOT a reason"));
        assertTrue(prompt.contains("a primary `card_id` MUST be given whenever status is `\"full\"` or `\"partial\"`"));
        // 1(d) period regime check
        assertTrue(prompt.contains("Period regime check"));
        assertTrue(prompt.contains("the primary is the card whose regime contains the notice's dates"));
        // 1(e) self-check list
        assertTrue(prompt.contains("## Self-check before you answer"));
        assertTrue(prompt.contains("`issues.length` equals `grounds.length`"));
        assertTrue(prompt.contains("Every numbered paragraph of the notice appears as a key of `para_map`"));
        assertTrue(prompt.contains("Every issue with status `\"full\"` or `\"partial\"` has a non-null `card_id`"));
        assertTrue(prompt.contains("No card id appears as both a primary and an alternate"));
    }

    @Test
    void outputExampleUsesCardIdAndAlternates_notCardIds() throws IOException {
        String prompt = resource(PROMPT);
        int start = prompt.indexOf("```json");
        int end = prompt.indexOf("```", start + 7);
        assertTrue(start >= 0 && end > start, "prompt has a fenced JSON example");
        String example = prompt.substring(start, end);
        assertTrue(example.contains("\"grounds\""));
        assertTrue(example.contains("\"card_id\""));
        assertTrue(example.contains("\"alternates\""));
        assertFalse(example.contains("card_ids"), "the v1 card_ids array must not appear in the v2 example");
        assertTrue(example.contains("\"para_map\""));
        assertTrue(example.contains("\"missing_or_doubtful\""));
    }

    @Test
    void promptKeepsTheIssueSplittingAndParaMapRules() throws IOException {
        String prompt = resource(PROMPT);
        assertTrue(prompt.contains("NEVER invent or fabricate values"));
        assertTrue(prompt.contains("One issue per distinct ground of demand"));
        assertTrue(prompt.contains("Consequential interest and penalty are part of the main issue, NOT separate issues"));
        assertTrue(prompt.contains("the notice's ONLY demand is interest or penalty"));
        assertTrue(prompt.contains("interest computed on the gross tax liability"));
        for (String provision : new String[]{"Section 50", "73(9)", "74(9)", "122", "171(3A)"}) {
            assertTrue(prompt.contains(provision), provision);
        }
        assertTrue(prompt.contains("Map EVERY numbered paragraph of the notice"));
        assertTrue(prompt.contains("Introductory / background paragraphs"));
        assertTrue(prompt.contains("The \"show cause why\" / demand paragraph maps to ALL issues"));
    }

    @Test
    void matchingEffortDefaultsToMedium() {
        AppProperties props = TestAppProperties.fromApplicationYml();
        if (System.getenv("LLM_EFFORT_MATCHING") == null) {
            assertEquals("medium", props.getLlm().effortForAgent("matching"));
        }
    }

    @Test
    void answerKeysFollowTheSplittingRules() throws IOException {
        ObjectMapper om = new ObjectMapper();
        int checked = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Path.of("src/test/resources/testbench/notices"), "*.json")) {
            for (Path f : files) {
                JsonNode key = om.readTree(f.toFile()).path("answer_key");
                JsonNode issues = key.path("issues");
                String name = f.getFileName().toString();
                assertEquals(key.path("expected_issues").asInt(), issues.size(), name + ": expected_issues = number of issues");
                for (JsonNode issue : issues) {
                    String section = issue.path("key_facts").path("legal_section").asText("");
                    if (INTEREST_OR_PENALTY.matcher(section).find()) {
                        Integer allowed = SEPARATE_INTEREST_ISSUES.get(name);
                        assertEquals(allowed, issue.path("issue_no").asInt(),
                                name + ": interest / penalty issue " + issue.path("issue_no") + " must be folded into its main issue");
                    }
                }
                checked++;
            }
        }
        assertEquals(15, checked);
        assertEquals(Set.of("notice_04_fullmatch_sec50.json", "notice_06_hybrid_sec73_sec50.json"), SEPARATE_INTEREST_ISSUES.keySet());
    }

    private String resource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in, path).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
