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
 * Rules of the live matching prompt (prompts/issue_matching_v3.md, version v3.3: grounds-first splitting, one
 * primary card plus alternates, status decided on notice-visible DECISIVE facts only with the card's CLIENT facts
 * returned as client_facts_to_confirm, a card's stage tested against the response the input document calls for,
 * visible-defect grounds with a kind, written date-boundary checks for the chosen cards only, primary-only
 * not_this_if_checked, capped why) and the testbench answer keys that follow the splitting rules (live runs split
 * notices 001 and 010 differently from run to run). No model calls.
 */
class MatchingPromptRulesTest {

    private static final String PROMPT = "/prompts/" + com.noticedesk.api.agent.IssueMatchingAgent.PROMPT_FILE + ".md";

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
        assertEquals("issue_matching_v3_3", com.noticedesk.api.agent.IssueMatchingAgent.PROMPT_VERSION);
        assertEquals("issue_matching_v3", com.noticedesk.api.agent.IssueMatchingAgent.PROMPT_FILE, "v3.3 is edited in place in the v3 file");
        String v1 = resource("/prompts/issue_matching_v1.md");
        assertTrue(v1.contains("Assign up to 2-3 `card_ids` per issue"));
        assertFalse(v1.contains("alternates"));
        String v2 = resource("/prompts/issue_matching_v2.md");
        assertTrue(v2.contains("# System Prompt: GST Issue Matching Agent (v2)"));
        assertTrue(v2.contains("Period regime check"));
        assertFalse(v2.contains("\"kind\""), "v2 has no issue kind");
        assertFalse(v2.contains("Step 1b"), "v2 has no visible-defect step");
        String v3 = resource(PROMPT);
        assertTrue(v3.contains("# System Prompt: GST Issue Matching Agent (v3.3)"));
        assertFalse(v3.contains("Matching Agent (v3)\n"), "the heading carries the v3.3 version");
        assertFalse(v3.contains("Matching Agent (v3.1)"), "the heading carries the v3.3 version");
        assertFalse(v3.contains("Matching Agent (v3.2)"), "the heading carries the v3.3 version");
    }

    /**
     * v3.3 (live, 35 private notices: the cards describe the stage of the FIRM'S RESPONSE, the input document is the
     * DEPARTMENT'S document that the response answers; the model read a card's "APL-01 appeal stage" and wrote
     * "contradicted - the document is the DRC-07 order", downgrading a correct card to partial). The prompt now carries
     * an input-document to response-stage table, tests a card's stage against the response the input calls for, and
     * writes the test out in why.
     */
    @Test
    void v3_3_cardStageIsTestedAgainstTheResponseTheInputDocumentCallsFor() throws IOException {
        String prompt = resource(PROMPT);
        // the section sits right before the status rules
        int section = prompt.indexOf("### Stage of the response");
        int statusRules = prompt.indexOf("3. **Status rules (apply exactly).**");
        assertTrue(section > 0 && statusRules > section, "the stage section precedes the status rules");
        assertTrue(prompt.indexOf("2. **One primary card per issue, up to two alternates.**") < section);
        assertTrue(prompt.contains("The catalogue cards describe the stage of the FIRM'S RESPONSE"));
        assertTrue(prompt.contains("The input document is the DEPARTMENT'S document that this response answers."));
        // (a) the table header and every row of the live finding
        assertTrue(prompt.contains("| Input document (the department's) | Response stage the card describes |"));
        for (String row : new String[]{
                "| DRC-07 order (summary of order under Section 73 / 74 / 74A) | APL-01 appeal |",
                "| RFD-06 refund rejection order | APL-01 appeal |",
                "| REG-19 cancellation order | REG-21 revocation application, or APL-01 appeal |",
                "| MOV-09 penalty order (Section 129) | APL-01 appeal |",
                "| REG-17 SCN for cancellation of registration | REG-18 reply to the SCN |",
                "| RFD-08 refund rejection notice | RFD-09 reply |",
                "| ASMT-10 scrutiny notice | ASMT-11 reply |",
                "| DRC-01A intimation (Rule 142(1A)) | DRC-01A Part B reply |",
                "| DRC-01 / Section 73 / 74 / 74A SCN | reply to the SCN (DRC-06) |",
                "| DRC-22 provisional attachment (Section 83) | Rule 159(5) objection |",
                "| Recovery letter / DRC-13 garnishee | representation to the proper officer |"}) {
            assertTrue(prompt.contains(row), row);
        }
        // (b) the rule: STAGES and stage words describe the response; satisfied per the table; never contradicted by the input's own name
        assertTrue(prompt.contains("A card's STAGES value and any stage word in its DECISIVE text (\"appeal\", \"APL-01\", \"reply\", \"RFD-09\", \"REG-21\", \"objection\", \"representation\") describe the RESPONSE the firm will draft."));
        assertTrue(prompt.contains("They are satisfied when the input document is the one that this response answers per the table."));
        assertTrue(prompt.contains("The stage is NEVER \"contradicted\" merely because the input document is the order, notice or intimation itself"));
        assertTrue(prompt.contains("a card for an APL-01 appeal is satisfied by a DRC-07 order"));
        // (c) contradicted only when the input calls for a different response
        assertTrue(prompt.contains("The stage is contradicted only when the input document calls for a DIFFERENT response per the table"));
        assertTrue(prompt.contains("a card for an APL-01 appeal when the input is a DRC-01 SCN that still awaits a reply, or a reply card when the input is already a confirmed order"));
        // (d) sibling cards that differ only by stage
        assertTrue(prompt.contains("When two sibling cards differ only by response stage"));
        assertTrue(prompt.contains("pick the primary by the table from the input document's form name and heading, and put the other sibling in `alternates`"));
        // (e) the written stage test, in the rule, in the example and in the self-check
        assertTrue(prompt.contains("`stage: <input document> -> <response> vs card <stage> -> satisfied|not satisfied`"));
        assertTrue(prompt.contains("`stage: DRC-07 order -> APL-01 appeal vs card APL-01 appeal -> satisfied`"));
        assertTrue(prompt.contains("stage: DRC-01 SCN -> reply vs card reply -> satisfied. tax period:"));
        // the status-rule Stage bullet now tests the response stage, and the self-check carries the new line
        assertTrue(prompt.contains("The stage test counts as a DECISIVE fact and is made against that RESPONSE stage, never against the input document's own name"));
        assertFalse(prompt.contains("a card whose STAGES do not include the document's stage is at most an alternate"), "v3.2 wording: the input document's stage");
        assertFalse(prompt.contains("a card whose DECISIVE text names a different form or stage cannot be"), "v3.2 wording");
        assertTrue(prompt.contains("Every stage test was made against the response stage from the table, not against the input document's own name"));
    }

    /**
     * v3.2 (live: a third of correct primary cards on 35 private notices were "partial" because a client-side fact,
     * which the department's document can never state, was reported "absent from the notice"). The card SUMMARY now
     * separates DECISIVE (notice-visible) from CLIENT facts; status is decided on DECISIVE facts and testable NOT
     * THIS IF rules only; CLIENT facts come back as client_facts_to_confirm; an untestable rule no longer lowers the
     * status unless it is a date boundary whose date the notice does not state.
     */
    @Test
    void v3_2_statusOnNoticeVisibleFactsOnly_clientFactsBecomeAConfirmList() throws IOException {
        String prompt = resource(PROMPT);
        // (a) the new SUMMARY shape and the two roles
        assertTrue(prompt.contains("\"ALLEGES: ... DECISIVE: <facts visible in the department's document> CLIENT: <facts to confirm with the client; 'none' if none> DEFENCE: ...\""));
        assertFalse(prompt.contains("DECISIVE: <facts required for a full match> DEFENCE"), "v3.1 SUMMARY shape");
        assertTrue(prompt.contains("**DECISIVE** facts are facts the department's own document states or shows"));
        assertTrue(prompt.contains("they alone decide the status"));
        assertTrue(prompt.contains("**CLIENT** facts are facts only the taxpayer can supply"));
        assertTrue(prompt.contains("a CLIENT fact that is absent from the notice is EXPECTED to be absent"));
        for (String example : new String[]{"no consignment note was issued", "recipient holds the invoices and bank proof of payment",
                "nil returns were filed", "the supplier was active on the portal", "interest and late fee were paid"}) {
            assertTrue(prompt.contains(example), example);
        }
        // (b) status rules: DECISIVE + testable NOT THIS IF only; CLIENT never affects status
        assertTrue(prompt.contains("Status is decided ONLY on the card's DECISIVE facts (notice-visible) and on its NOT THIS IF rules that can be tested from the document."));
        assertTrue(prompt.contains("CLIENT facts NEVER affect status, in either direction"));
        assertTrue(prompt.contains("NONE of its testable NOT THIS IF rules is triggered"));
        assertTrue(prompt.contains("still `\"full\"` when every CLIENT fact of the card is absent from the notice"));
        assertTrue(prompt.contains("A CLIENT fact is never that fact."));
        assertTrue(prompt.contains("a `\"partial\"` whose only reason is a CLIENT fact is invalid"));
        assertTrue(prompt.contains("a missing annexure or an absent CLIENT fact is never a reason for `\"partial\"`"));
        // (c) untestable rules: "cannot test" has no effect, except a date boundary whose date is not stated
        assertTrue(prompt.contains("**Untestable rules.**"));
        assertTrue(prompt.contains("is recorded as `cannot test` and has NO effect on status: it does not downgrade a `\"full\"` match"));
        assertTrue(prompt.contains("The ONLY untestable check that makes a match `\"partial\"` is a DATE boundary of the card (section 5) that cannot be tested because the notice does not state the date"));
        assertTrue(prompt.contains("A DATE boundary that cannot be tested because the notice does not state the date is written as `-> cannot test (date not stated)` and makes the match at most `\"partial\"`"));
        assertTrue(prompt.contains("This is the only \"cannot test\" that affects status"));
        assertFalse(prompt.contains("A boundary that cannot be tested because the notice does not state the date is written as"), "v3.1 wording: any boundary");
        // (d) the new per-issue field
        assertTrue(prompt.contains("**`client_facts_to_confirm`.**"));
        assertTrue(prompt.contains("copy the primary card's CLIENT facts into `client_facts_to_confirm`, one short line per fact in the card's own words, leaving out any CLIENT fact the notice itself already evidences"));
        assertTrue(prompt.contains("The list is empty (`[]`) when the card says `CLIENT: none`, when the card has no CLIENT part, or when status is `\"none\"`."));
        assertTrue(prompt.contains("Never put a DECISIVE fact in this list, and never put a CLIENT fact in `why` as a reason for the status."));
        // (e) partial still names the DECISIVE fact; self-check lines
        assertTrue(prompt.contains("DECISIVE \"<quoted card text>\": <absent|contradicted|exceeded> - <what the notice says instead>"));
        assertTrue(prompt.contains("No issue is partial because of a CLIENT fact."));
        assertTrue(prompt.contains("No `not_this_if_checked` entry that reads \"cannot test\" lowered a status, except a date boundary written `-> cannot test (date not stated)`."));
        assertTrue(prompt.contains("has a `client_facts_to_confirm` list holding the primary card's CLIENT facts in the card's words, minus any the notice already evidences; it is `[]` for `CLIENT: none` and for status `\"none\"`; it holds no DECISIVE fact."));
        // (f) v3.1 rules kept: grounds, defects, boundary lines for primary + alternates only, 60-word why
        assertTrue(prompt.contains("Step 1b, **visible defects**"));
        assertTrue(prompt.contains("WRITE lines only for the chosen primary card and for each listed alternate (at most 2)"));
        assertTrue(prompt.contains("`why` is at most 60 words of prose plus the boundary lines of section 5"));
        assertTrue(prompt.contains("`not_this_if_checked` lists ONLY the primary card's NOT THIS IF rules"));
    }

    /**
     * v3.1 trims the written checks to the chosen cards (live: v3 doubled output tokens, 2k to 5k, one max_tokens
     * stop) and guards two over-eager defect grounds. The mechanism (written boundary lines, no primary with a
     * "not satisfied" line) stays.
     */
    @Test
    void v3_1_writtenChecksOnlyForTheChosenCards_whyCapped_defectGuards() throws IOException {
        String prompt = resource(PROMPT);
        // (a) boundary lines for the primary and the listed alternates only; rejected candidates get none
        assertTrue(prompt.contains("WRITE lines only for the chosen primary card and for each listed alternate (at most 2), and only for boundaries that actually exist on those cards"));
        assertTrue(prompt.contains("Candidates that were considered and rejected get no lines."));
        assertFalse(prompt.contains("For EVERY candidate card (primary and alternates)"), "v3 wording: lines for every candidate");
        assertTrue(prompt.contains("A card with any boundary line that reads \"not satisfied\" can NEVER be the primary"));
        // (b) not_this_if_checked: the primary card's rules only, one short entry per rule
        assertTrue(prompt.contains("`not_this_if_checked` lists ONLY the primary card's NOT THIS IF rules, one short entry per rule in the form `<rule in 12 words or fewer> - triggered|not triggered|cannot test`"));
        assertTrue(prompt.contains("No entries for alternates or for rejected candidates."));
        assertFalse(prompt.contains("for the primary (and the alternates) under `not_this_if_checked`"), "v3 wording: rules of the alternates too");
        // (c) why: 60 words of prose plus the boundary lines; cite paragraphs instead of restating the notice
        assertTrue(prompt.contains("`why` is at most 60 words of prose plus the boundary lines of section 5"));
        assertTrue(prompt.contains("Do not restate the notice; cite paragraph numbers."));
        // (d) defect guards: DIN / signature present in the header; "proper officer" only with a stated designation and a card rule
        assertTrue(prompt.contains("A defect ground is raised only when the document itself shows the defect; a department header that carries a DIN, a reference number and a signed or digitally signed block has no DIN/signature defect."));
        assertTrue(prompt.contains("Do not raise a \"proper officer\" defect unless the document states the officer's designation AND the monetary limit or jurisdiction rule the designation breaches is stated on a card."));
        // (e) self-check items follow (a) and (b)
        assertTrue(prompt.contains("every NOT THIS IF rule was tested and listed in `not_this_if_checked` (one short entry per rule; no entries for alternates)"));
        assertTrue(prompt.contains("boundary of the primary card and of each listed alternate was tested and written out in `why`"));
        assertTrue(prompt.contains("rejected candidates have no boundary lines"));
        assertFalse(prompt.contains("boundary of every candidate card was tested"), "v3 self-check wording");
        assertTrue(prompt.contains("`why` is at most 60 words of prose plus its boundary lines and cites paragraph numbers"));
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
        // v3.1 trimmed form: short primary-only not_this_if_checked entries, paragraph citations instead of restating
        assertTrue(example.contains("\"not_this_if_checked\": [\"Not this if period on or after 01.01.2022 (use CARD-002) - not triggered\", \"Not this if the supplier has since filed GSTR-1 - cannot test\"]"));
        assertTrue(example.contains("\"not_this_if_checked\": [\"Not this if issued by a State GST authority - not triggered\"]"));
        assertFalse(example.contains("CARD-002: Not this if"), "no not_this_if_checked entry for the alternate");
        assertTrue(example.contains("(paras 3, 3.1, 4)"));
        // v3.2: client_facts_to_confirm on every issue (facts for the demand issue, [] for the defect); an untestable
        // client-side NOT THIS IF rule is "cannot test" and the issue stays full
        assertEquals(2, example.split("\"client_facts_to_confirm\"", -1).length - 1, "one client_facts_to_confirm per issue");
        assertTrue(example.contains("\"client_facts_to_confirm\": [\"Recipient holds the tax invoices and bank proof of payment to the supplier\", \"Supplier was active on the portal on the invoice dates\"]"));
        assertTrue(example.contains("\"client_facts_to_confirm\": []"));
        assertTrue(example.contains("\"Not this if the supplier has since filed GSTR-1 - cannot test\"]"));
        assertFalse(example.contains("\"status\": \"partial\""), "the example has no partial: both issues stay full");
        for (String why : whyValues(example)) {
            // sentences end in ". " (dates like 02.05.2024 have no space after the dot); boundary lines carry "->"
            int words = 0;
            for (String sentence : why.split("\\.\\s+")) {
                if (!sentence.contains("->") && !sentence.isBlank()) words += sentence.trim().split("\\s+").length;
            }
            assertTrue(words <= 60, "why prose must be 60 words or fewer, was " + words + ": " + why);
        }
    }

    private static java.util.List<String> whyValues(String example) {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("\"why\": \"((?:[^\"\\\\]|\\\\.)*)\"").matcher(example);
        while (m.find()) out.add(m.group(1));
        assertEquals(2, out.size(), "two why values in the example");
        return out;
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
