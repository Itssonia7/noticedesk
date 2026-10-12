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
 * Issue splitting rules of prompts/issue_matching_v1.md (live runs split notices 001 and 010 differently
 * from run to run) and the testbench answer keys that follow them. No model calls.
 */
class MatchingPromptRulesTest {

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
    void promptStatesTheIssueSplittingAndParaMapRules() throws IOException {
        String prompt = resource("/prompts/issue_matching_v1.md");
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
