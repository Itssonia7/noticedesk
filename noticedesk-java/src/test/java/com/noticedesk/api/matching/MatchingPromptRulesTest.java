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
 * Rules of the live matching prompt (prompts/issue_matching_v3.md: grounds-first splitting, one primary card
 * plus alternates, decisive-fact status, visible-defect grounds with a kind, written date-boundary checks)
 * and the testbench answer keys that follow the splitting rules (live runs split notices 001 and 010
 * differently from run to run). No model calls.
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
    void agentLoadsTheV3Prompt_andV1V2StayUntouched() throws IOException {
        assertEquals("issue_matching_v3", com.noticedesk.api.agent.IssueMatchingAgent.PROMPT_VERSION);
        String v1 = resource("/prompts/issue_matching_v1.md");
        assertTrue(v1.contains("Assign up to 2-3 `card_ids` per issue"));
        assertFalse(v1.contains("alternates"));
        String v2 = resource("/prompts/issue_matching_v2.md");
        assertTrue(v2.contains("# System Prompt: GST Issue Matching Agent (v2)"));
        assertTrue(v2.contains("Period regime check"));
        assertFalse(v2.contains("\"kind\""), "v2 has no issue kind");
        assertFalse(v2.contains("Step 1b"), "v2 has no visible-defect step");
        String v3 = resource(PROMPT);
        assertTrue(v3.contains("# System Prompt: GST Issue Matching Agent (v3)"));
    }

    @Test
    void v3_visibleDefectGroundsWithAKind() throws IOException {
        String prompt = resource(PROMPT);
        // issue types
        assertTrue(prompt.contains("`\"demand\"`: a ground of demand or action stated by the department"));
        assertTrue(prompt.contains("`\"defect\"`: a procedural, jurisdictional, limitation, service, signature, DIN, hearing or form defect that is VISIBLE in the document itself"));
        assertTrue(prompt.contains("Step 1b, **visible defects**"));
        assertTrue(prompt.contains("scan the document for visible defects, each as its own ground with kind `\"defect\"`"));
        // the defect checklist
        for (String item : new String[]{
                "DIN / RFN missing, blank or a placeholder on a central-tax document dated on or after 08.11.2019",
                "Document unsigned, or not digitally signed / authenticated",
                "SCN date vs the Section 73(10) / 74(10) / 74A limitation for the financial year: compute the deadline",
                "Section 74 SCN dated before the GSTR-9 due date of that financial year",
                "No DRC-01A (intimation under Rule 142(1A)) before a DRC-01 whose SCN date falls in the mandatory window 09.10.2019 to 14.10.2020",
                "No personal hearing offered",
                "Section 75(4)",
                "Reply time shorter than the statutory minimum",
                "Issuing officer's rank or jurisdiction not matching the power exercised",
                "Recovery, bank attachment or garnishee (DRC-13, Section 79, Section 83) without an adjudicated demand",
                "Order with no reasons on the taxpayer's reply",
                "Refund rejection without RFD-08 (notice) / RFD-09 (reply) / a hearing"}) {
            assertTrue(prompt.contains(item), item);
        }
        // placeholder DIN rule, no-invention rule, header paras, one issue per defect
        assertTrue(prompt.contains("A placeholder such as \"[____]\", \"XXXX\", \"not generated\", \"N/A\" or an empty DIN field IS a missing DIN"));
        assertTrue(prompt.contains("Rule: do NOT invent a defect. The date, field or omission must be visible in the text"));
        assertTrue(prompt.contains("use `\"header\"` when the defect sits in the DIN / date / signature block"));
        assertTrue(prompt.contains("Each defect ground is matched to a card exactly like a demand ground (`card_id`, `alternates`, `status`, `why`, `not_this_if_checked`, `notice_paras`)"));
        assertTrue(prompt.contains("Never fold a defect into a demand issue"));
        assertTrue(prompt.contains("(e) **Each visible defect is its own ground** with kind `\"defect\"`"));
        // para_map: header defects need no entry; demand paragraphs map to all DEMAND issues
        assertTrue(prompt.contains("Defect grounds that have no numbered paragraph (DIN / date / signature block, `\"header\"`) need no `para_map` entry"));
        assertTrue(prompt.contains("The \"show cause why\" / demand paragraph maps to ALL demand issues"));
    }

    @Test
    void v3_dateBoundaryLinesAreWrittenOut_andPartialMustCiteTheDecisiveFact() throws IOException {
        String prompt = resource(PROMPT);
        // date-boundary discipline replaces the v2 period-regime section
        assertTrue(prompt.contains("Date-boundary discipline"));
        assertFalse(prompt.contains("Period regime check"));
        assertTrue(prompt.contains("`<fact>: <notice value> vs <card boundary> -> <satisfied|not satisfied>`"));
        assertTrue(prompt.contains("you MUST write in `why` the notice value you tested and the boundary you tested against, one line per boundary"));
        assertTrue(prompt.contains("A card with any boundary line that reads \"not satisfied\" can NEVER be the primary"));
        // the two explicit examples
        assertTrue(prompt.contains("Example A, Section 62(2) sibling cards (30-day vs 60-day return window)"));
        assertTrue(prompt.contains("order service date: 21.07.2023 vs before 01.10.2023 -> satisfied"));
        assertTrue(prompt.contains("order service date: 21.07.2023 vs on or after 01.10.2023 -> not satisfied"));
        assertTrue(prompt.contains("Example B, Section 73(5)/(6) vs Section 73(8) sibling cards"));
        assertTrue(prompt.contains("Tax paid BEFORE the SCN"));
        assertTrue(prompt.contains("vs within 30 days after SCN -> not satisfied"));
        assertTrue(prompt.contains("the primary is the Section 73(5)/(6) card"));
        // limitation computed for the SCN-date defect
        assertTrue(prompt.contains("Section 73(10) order within 3 years, Section 74(10) within 5 years, from the GSTR-9 due date"));
        assertTrue(prompt.contains("SCN date: <date> vs Section 73(2)/(10) SCN deadline <date> for FY <year> -> satisfied|not satisfied"));
        // status discipline
        assertTrue(prompt.contains("**Partial discipline.**"));
        assertTrue(prompt.contains("quoting the card's DECISIVE text for that fact"));
        assertTrue(prompt.contains("DECISIVE \"<quoted card text>\": <absent|contradicted|exceeded> - <what the notice says instead>"));
        assertTrue(prompt.contains("If every DECISIVE fact is present, the status is `\"full\"` even when the notice's amounts, DIN, dates of hearing or annexures are missing, and even when alternates exist"));
        assertTrue(prompt.contains("is decided from the document's form name and heading"));
        assertTrue(prompt.contains("The stage test counts as a DECISIVE fact"));
        // self-check additions
        assertTrue(prompt.contains("each issue's `kind` equals its ground's `kind`"));
        assertTrue(prompt.contains("Every visible defect found in Step 1b is its own ground and issue with kind `\"defect\"`"));
        assertTrue(prompt.contains("Every `\"partial\"` quotes in `why` the DECISIVE fact that is absent or contradicted"));
        assertTrue(prompt.contains("was tested and written out in `why` as `<fact>: <notice value> vs <card boundary> -> satisfied|not satisfied`"));
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
        // 1(d) date boundaries decide between sibling cards (v3: written out per boundary)
        assertTrue(prompt.contains("The primary is the card whose every boundary is satisfied"));
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
        assertFalse(example.contains("card_ids"), "the v1 card_ids array must not appear in the example");
        assertTrue(example.contains("\"para_map\""));
        assertTrue(example.contains("\"missing_or_doubtful\""));
        // v3: one demand ground + one defect ground (missing DIN), the written date lines, header paras
        assertTrue(example.contains("\"kind\": \"demand\""));
        assertTrue(example.contains("\"kind\": \"defect\""));
        assertTrue(example.contains("DIN missing"));
        assertTrue(example.contains("\"notice_paras\": [\"header\"]"));
        assertTrue(example.contains("vs PERIOD 01.07.2017 to 31.12.2021 -> satisfied"));
        assertTrue(example.contains("-> not satisfied (alternate only)"));
        assertTrue(example.contains("document date: 02.05.2024 vs on or after 08.11.2019 -> satisfied"));
        assertFalse(example.contains("\"header\": ["), "a header-only defect has no para_map entry");
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
        assertTrue(prompt.contains("The \"show cause why\" / demand paragraph maps to ALL demand issues"));
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
