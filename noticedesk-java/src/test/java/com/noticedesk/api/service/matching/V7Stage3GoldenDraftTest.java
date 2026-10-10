package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IndianKanoonClient;
import com.noticedesk.api.agent.IndianKanoonClient.IkDoc;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialSectionAddition;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.DraftingPipelineResult;
import com.noticedesk.api.service.V7DraftingPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Golden drafts for testbench notice_07 (partial match) and notice_10 (no match) through the full v7
 * pipeline. Every notice fact (name, GSTIN, state, reference, date, amounts, section, paragraph count)
 * is read from the testbench JSON; Stage 1, the AI writers and IndianKanoon are mocks (no live calls).
 *
 * <p>The snapshot files are compared, not overwritten. Regenerate with {@code -Dgolden.update=true}.
 */
class V7Stage3GoldenDraftTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/testbench/golden");
    /** GSTIN state codes used by the testbench notices. */
    private static final Map<String, String> STATE_BY_CODE = Map.of("07", "Delhi", "24", "Gujarat", "27", "Maharashtra");

    private final ObjectMapper om = new ObjectMapper();
    private IssueMatchingAgent matchingAgent;
    private PartialDraftingAgent partialAgent;
    private NewIssueDraftingAgent newIssueAgent;
    private IndianKanoonClient ikClient;
    private V7DraftingPipelineService pipeline;

    /** Facts of one testbench notice, all taken from its JSON file. */
    record NoticeFacts(String noticeId, String documentType, String ocr, String reference, String date, String clientName,
                       String gstin, String state, String financialYear, String section, String replyWithin,
                       int paraCount, JsonNode answerIssues, double totalDemand) {}

    @BeforeEach
    void setUp() {
        TemplateFillService fill = new TemplateFillService(null, null, om);
        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        matchingAgent = mock(IssueMatchingAgent.class);
        partialAgent = mock(PartialDraftingAgent.class);
        newIssueAgent = mock(NewIssueDraftingAgent.class);
        ikClient = mock(IndianKanoonClient.class);
        HighStakesDraftingAgent highStakesAgent = mock(HighStakesDraftingAgent.class); // isHighStakesActive -> false

        AppProperties props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken("test-token-not-real");
        CitationVerificationAgent citationAgent = new CitationVerificationAgent(om, props, null, null, ikClient);

        pipeline = new V7DraftingPipelineService(matchingAgent, null, om, fill, assembly, new DraftCheckService(null),
                partialAgent, newIssueAgent, highStakesAgent, citationAgent);

        // Same content as seeds/dev/seed_reply_and_stage_templates_dev.sql (TPL-006, stage scn_73)
        fill.registerInMemoryTemplate(new ReplyTemplate(
                "TPL-006", 1, "CARD-006", LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                List.of(
                        new TemplateBlock("BLK-006-04A", 4, "<p>[DEV - not CA reviewed] It is submitted that the discrepancy of Rs {{issue.amount_tax}} in GSTR-2B was due to {{issue.reason_for_difference}}, and {{issue.section16_conditions_statement}} by {{client.legal_name}}.</p>", List.of("CIT-001"), null),
                        new TemplateBlock("BLK-006-06A", 6, "<p>[DEV - not CA reviewed] Section 16(2)(aa) cannot be applied retroactively or punitively where genuine tax has been deposited by the supplier into the public exchequer.</p>", List.of("CIT-001"), null)),
                List.of("issue.amount_tax", "notice.din", "client.legal_name", "issue.reason_for_difference", "issue.section16_conditions_statement"),
                List.of("GSTR-2B reconciliation report", "Supplier confirmation certificates"),
                "Input Tax Credit disallowance under Section 16(2)(aa) based on GSTR-2B static statement discrepancy of Rs {{issue.amount_tax}}.",
                List.of(), null, null, null));
        assembly.registerInMemoryStageTemplate(new StageTemplate(
                "scn_73", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                Map.of(
                        "08", "<p>[DEV - not CA reviewed] Request for cross-examination of departmental audit officers or third parties whose material is relied upon.</p>",
                        "12", "<p>[DEV - not CA reviewed] PRAYER: It is humbly prayed that the proceedings initiated by notice {{notice.reference}} dated {{notice.issue_date}} under {{notice.section}} be dropped in full and no penalty or interest be levied. It is further requested that an opportunity of personal hearing be granted before passing any adverse order as mandated under Section 75(4) of the CGST Act, 2017.</p>",
                        "14", "<p>[DEV - not CA reviewed] CLIENT SUMMARY: {{notice.type}} proposing a demand. Reply required before due date.</p>",
                        "15", "<p>[DEV - not CA reviewed] FILING CHECKLIST: 1. Written Submissions 2. Form GST DRC-06 3. Authorisation Letter 4. Document Annexures.</p>"),
                null, null, null));
    }

    // ------------------------------------------------------------------ notice_07: capital goods ITC (partial)

    @Test
    void notice07PartialMatchCapitalGoodsItc() throws IOException {
        NoticeFacts n = loadNotice("notice_07_partial_novel_machinery.json");
        assertEquals("Section 73", n.section());

        // Stage 1 output (mock), facts from the answer key; para map classifies the notice's 6 paragraphs
        Map<String, List<Integer>> paraMap = Map.of("record", List.of(1), "issue", List.of(2, 3, 4), "demand", List.of(5), "directions", List.of(6));
        mockStage1(n, paraMap, List.of("2", "3", "4"), "ITC on capital goods disallowed for missing machine serial / chassis numbers on invoices");

        // Partial writer (mock): the GSTR-2B blocks of CARD-006 do not fit, so nothing is anchored to them
        String amt = inr(n.answerIssues().get(0).path("key_facts").path("amount").asDouble());
        when(partialAgent.generatePartialDrafting(any(), any(), any(), anyString(), any(), anyBoolean()))
                .thenReturn(new PartialDraftingResult(
                        List.of(
                                new PartialSectionAddition(4, null,
                                        "<p>The Noticee availed Input Tax Credit of " + amt + " on capital goods described in the notice as specialised industrial generator units and heavy mobile crane equipment. The notice does not allege that the goods were not received or were not used in the course or furtherance of business; its only objection is that the purchase tax invoices and delivery receipts do not carry manufacturer machine registration serial numbers and equipment chassis numbers.</p>"
                                        + "<p>Rule 46 of the CGST Rules, 2017 prescribes the particulars of a tax invoice, such as the description, HSN code, quantity, taxable value and tax charged. It does not require a machine serial number or a chassis number. Omission of these identifiers is not a defect under Rule 46 and does not take the invoices outside Section 16(2)(a).</p>"
                                        + "<p>The Noticee holds the tax invoices for the capital goods [[MISSING: invoice_numbers_and_dates]] and evidence of their receipt [[MISSING: goods_receipt_evidence]].</p>"),
                                new PartialSectionAddition(5, null,
                                        "<li><strong>Para 2:</strong> It is admitted that Input Tax Credit of " + amt + " was availed on the capital goods described. That the credit is ineligible is denied.</li>"
                                        + "<li><strong>Para 3:</strong> Denied. Rule 46 of the CGST Rules, 2017 does not require machine serial numbers or chassis numbers on a tax invoice.</li>"
                                        + "<li><strong>Para 4:</strong> Denied. The proposed disallowance under Section 16(2)(a) has no basis, as the Noticee holds tax invoices for the goods.</li>"
                                        + "<li><strong>Para 5:</strong> Denied. As the credit is admissible, nothing is recoverable under Section 73(1), and no interest under Section 50 or penalty under Section 73(9) arises.</li>"),
                                new PartialSectionAddition(6, null,
                                        "<p>Section 16(2)(a) of the CGST Act, 2017 requires the recipient to be in possession of a tax invoice. The particulars of a tax invoice are those listed in Rule 46; identifiers outside that list cannot be read into it.</p>"
                                        + "<p>Even where a document does not contain every specified particular, the proviso to Rule 36(2) of the CGST Rules, 2017 allows credit when it contains the amount of tax charged, description of goods, total value of supply, GSTINs of the supplier and recipient and, for inter-State supplies, the place of supply.</p>")),
                        List.of(),
                        List.of("Purchase tax invoices for the generator units and mobile crane equipment on which the credit was availed",
                                "Delivery challans / goods receipt notes for the capital goods",
                                "Fixed asset register extract showing capitalisation of the capital goods",
                                "Proof of payment to the suppliers"),
                        "Input Tax Credit of " + amt + " on capital goods is admissible: Rule 46 does not require machine serial or chassis numbers on a tax invoice.",
                        "test-model-partial", "partial_drafting_v2"));

        DraftingPipelineResult result = pipeline.runPipeline(inputFor(n));
        String text = render("=== NOTICE 07 V7 STAGE 3 GOLDEN DRAFT (PARTIAL MATCH: CAPITAL GOODS ITC) ===", result);

        assertNoticeFacts(n, result, text);
        assertTrue(text.contains("Rs 4,00,000"));
        assertTrue(text.contains("State of Gujarat"));
        assertFalse(clientFacing(result).contains("GSTR-2B"), "CARD-006 GSTR-2B blocks do not apply to this notice");
        assertFalse(clientFacing(result).contains("Supplier confirmation certificates"), "unused template's document list must not appear");
        assertTrue(section(result, 10).contains("Purchase tax invoices for the generator units"));
        assertTrue(section(result, 14).contains("Within 30 days from service"), "the notice says 'from service'");
        verify(partialAgent).generatePartialDrafting(argThat(i -> i.issueNo() == 1), any(), argThat(t -> t != null && "TPL-006".equals(t.templateId())), eq("scn_73"), any(), eq(false));
        verifyNoInteractions(newIssueAgent);

        compareOrUpdate("notice_07_v7_assembled_golden.txt", text);
    }

    // ------------------------------------------------------------------ notice_10: anti-profiteering, Section 171 (no match)

    @Test
    void notice10NoMatchAntiProfiteeringSection171() throws Exception {
        NoticeFacts n = loadNotice("notice_10_nomatch_anti_profiteering.json");
        assertEquals("Section 171", n.section());

        Map<String, List<Integer>> paraMap = Map.of("record", List.of(2), "issue", List.of(1, 3, 4), "demand", List.of(5), "directions", List.of(6));
        mockStage1(n, paraMap, List.of("1", "3", "4"), "Alleged failure to pass on GST rate reduction on televisions (Section 171)");

        String amt = inr(n.answerIssues().get(0).path("key_facts").path("amount").asDouble());
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(new NewIssueDraftingResult(1,
                        "<p>The Noticee denies that it has profiteered within the meaning of Section 171 of the CGST Act, 2017. The notice alleges that the base selling price of television units was increased simultaneously with the reduction of the GST rate on smart televisions from 28% to 18% effective 15-11-2017, keeping the MRP unchanged. Section 171(1) requires that a reduction in the rate of tax be passed on by way of a commensurate reduction in prices; it does not bar price revisions made for independent commercial reasons.</p>"
                                + "<p>The notice is issued for Financial Year " + n.financialYear() + ", whereas the rate reduction relied upon took effect on 15-11-2017. The notice does not state the period over which the profiteered amount of " + amt + " was computed, nor the method of computation. The Noticee requests a copy of the DGAP investigation report and the computation, to the extent not already supplied.</p>"
                                + "<p>The reasons for the revision of base prices and the supporting cost data are [[MISSING: reasons_and_cost_data_for_base_price_revision]].</p>",
                        "<li><strong>Para 1:</strong> Denied. The Noticee denies that it failed to pass on the benefit of the GST rate reduction on television sets.</li>"
                                + "<li><strong>Para 3:</strong> Denied. An increase in base price is not by itself profiteering; the reasons for the revision are set out in the reply.</li>"
                                + "<li><strong>Para 4:</strong> The requirement of Section 171 is a matter of law. Its application to the Noticee on the facts alleged is denied.</li>"
                                + "<li><strong>Para 5:</strong> Denied. The profiteered amount of " + amt + " is not established, interest is not leviable, and penalty under Section 171(3A) cannot be imposed for any period before that sub-section came into force.</li>",
                        "<p>Section 171(1) of the CGST Act, 2017 obliges a supplier to pass on the benefit of a reduction in the rate of tax by a commensurate reduction in prices. The burden of establishing the amount not passed on, and the period and method of its computation, lies on the authority proposing recovery.</p>"
                                + "<p>The Hon'ble Delhi High Court in Reckitt Benckiser India Pvt Ltd v. Union of India upheld the constitutional validity of Section 171; the computation of any profiteered amount must nonetheless be established on the facts of each case.</p>"
                                + "<p>Section 171(3A), which provides for penalty, was inserted with effect from 01-01-2020. Penalty cannot be imposed under it for any profiteering alleged in respect of a period before that date.</p>",
                        List.of(new RawAiCitation("Reckitt Benckiser India Pvt Ltd v. Union of India", "Delhi High Court", 2024, null,
                                "Constitutional validity of Section 171")),
                        "Defence rests on (i) the absence of a stated computation period and method, (ii) the gap between the 2017 rate change and FY 2022-23, and (iii) the prospective operation of Section 171(3A). Cost data justifying the base price revision is the critical evidence; without it the defence is weak on merits. Partner to verify jurisdiction: anti-profiteering functions were transferred to the Competition Commission of India from 01-12-2022, while this notice is issued in the name of the National Anti-Profiteering Authority on 05-06-2023.",
                        List.of("Price lists for the television models before and after 15-11-2017",
                                "Cost data supporting the revision of base prices",
                                "Sales invoices for the period examined by the DGAP",
                                "Copy of the DGAP investigation report, if received"),
                        "The Noticee denies profiteering under Section 171; the proposed amount of " + amt + ", interest and penalty under Section 171(3A) are contested.",
                        "test-model-new-issue", "new_issue_v2"));

        when(ikClient.search(anyString(), eq("Reckitt Benckiser India Pvt Ltd v. Union of India")))
                .thenReturn(List.of(new IkDoc("ik-test-1", "Reckitt Benckiser India Private Limited vs Union Of India on 29 January, 2024",
                        "Delhi High Court", "2024-01-29")));

        DraftingPipelineResult result = pipeline.runPipeline(inputFor(n));
        String text = render("=== NOTICE 10 V7 STAGE 3 GOLDEN DRAFT (NO MATCH: ANTI-PROFITEERING, SECTION 171) ===", result);

        assertNoticeFacts(n, result, text);
        assertTrue(text.contains("Rs 15,00,000"));
        assertTrue(text.contains("State of Delhi"));
        for (int s : new int[]{1, 2, 12, 14}) {
            assertFalse(section(result, s).contains("Section 73"), "section " + s + " must not default to Section 73");
        }
        assertTrue(section(result, 12).contains("under Section 171"));
        assertTrue(section(result, 14).contains("Notice under Section 171"));
        assertTrue(section(result, 14).contains("Within 21 days from receipt"));
        assertFalse(clientFacing(result).contains("GSTR-3B"), "default ITC document list must not appear");
        assertFalse(clientFacing(result).contains("defence is weak"), "strength note only in Section 13");
        assertTrue(section(result, 13).contains("defence is weak"));
        assertEquals("VERIFIED", result.citations().get(0).status());
        assertEquals(1, result.aiIssueSections().size());
        // Stage template scn_73 (document_type in the JSON) does not match a Section 171 notice -> block flag
        assertFalse(check(result, "CHECK_PROCEEDING_CONSISTENT").passed());

        compareOrUpdate("notice_10_v7_assembled_golden.txt", text);
    }

    // ------------------------------------------------------------------ helpers

    private void assertNoticeFacts(NoticeFacts n, DraftingPipelineResult result, String text) {
        String client = clientFacing(result);
        assertTrue(text.contains(n.clientName()), "client name");
        assertTrue(text.contains(n.gstin()), "GSTIN");
        assertTrue(text.contains(n.reference()), "reference");
        assertTrue(text.contains(n.date()), "notice date");
        assertTrue(text.contains("Financial Year " + n.financialYear()), "financial year");
        assertTrue(section(result, 2).contains("under " + n.section() + " of the CGST Act, 2017"), "Section 02 uses the notice's section");
        assertTrue(section(result, 12).contains(n.section()), "prayer uses the notice's section");
        assertTrue(text.contains(inr(n.totalDemand())), "total demand");

        assertFalse(text.contains("PENDING_AI"), "no [[PENDING_AI]] may remain");
        assertFalse(client.matches("(?is).*\\bnull\\b(?!\\s+and\\s+void).*"), "no 'null' in client-facing sections");
        assertFalse(client.contains("[ref]"));
        assertFalse(client.contains("No template available"));
        assertFalse(client.contains("CARD-"));
        assertTrue(check(result, "CHECK_NO_UNRESOLVED_PLACEHOLDERS").passed(), check(result, "CHECK_NO_UNRESOLVED_PLACEHOLDERS").details().toString());
        assertTrue(check(result, "CHECK_FIGURE_CONSISTENCY").passed());

        // Para-wise reply covers exactly this notice's paragraphs and only issues that exist
        String s05 = section(result, 5);
        for (int p = 1; p <= n.paraCount(); p++) assertTrue(s05.contains("<strong>Para " + p + ":</strong>"), "Para " + p);
        assertFalse(s05.contains("<strong>Para " + (n.paraCount() + 1) + ":</strong>"));
        assertFalse(s05.matches("(?s).*Issue #[2-9].*"), "only Issue #1 exists");

        // No carry-over from notice_06
        assertFalse(text.contains("Vantage"));
        assertFalse(text.contains("SCN/MH/2023-24/606"));
        assertFalse(text.contains("Section 50 on the gross"));
    }

    private void mockStage1(NoticeFacts n, Map<String, List<Integer>> paraMap, List<String> issueParas, String why) {
        assertEquals(n.paraCount(), paraMap.values().stream().flatMap(List::stream).mapToInt(Integer::intValue).max().orElse(0),
                "para map must cover exactly the notice's paragraphs");
        List<MatchedIssue> issues = new ArrayList<>();
        for (JsonNode ai : n.answerIssues()) {
            Map<String, Object> facts = new LinkedHashMap<>();
            ai.path("key_facts").fields().forEachRemaining(e ->
                    facts.put(e.getKey(), e.getValue().isNumber() ? e.getValue().asDouble() : e.getValue().asText()));
            List<String> cards = new ArrayList<>();
            ai.path("card_ids").forEach(c -> cards.add(c.asText()));
            issues.add(new MatchedIssue(ai.path("issue_no").asInt(), ai.path("status").asText(), cards, why, List.of(), facts, issueParas, List.of()));
        }
        MatchedNoticeInfo info = new MatchedNoticeInfo(n.reference(), null, n.date(), n.replyWithin(), n.financialYear(),
                n.financialYear(), n.totalDemand(), null);
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any()))
                .thenReturn(new MatchingResult(info, issues, paraMap, List.of(), List.of(), "hash-" + n.noticeId(), 100, 100, 0, 0));
    }

    private DraftingInput inputFor(NoticeFacts n) {
        Map<String, Object> notice = new LinkedHashMap<>();
        notice.put("notice_id", n.noticeId());
        notice.put("document_type", n.documentType());
        notice.put("notice_number", n.reference());
        notice.put("issue_date", n.date());
        notice.put("section", n.section());
        notice.put("total_demand_amount", n.totalDemand());
        return new DraftingInput(UUID.randomUUID(), "formal", null, n.clientName(), null, null, "Regular", n.gstin(), n.state(),
                "CGST Act", n.financialYear(), null, notice, Map.of(), n.ocr(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null);
    }

    private NoticeFacts loadNotice(String file) throws IOException {
        JsonNode root;
        try (InputStream is = getClass().getResourceAsStream("/testbench/notices/" + file)) {
            root = om.readTree(Objects.requireNonNull(is, file));
        }
        String ocr = root.path("full_ocr_text").asText();
        String gstin = find(ocr, "GSTIN:\\s*([0-9A-Z]{15})");
        double total = 0;
        for (JsonNode i : root.path("answer_key").path("issues")) total += i.path("key_facts").path("amount").asDouble();
        return new NoticeFacts(
                root.path("notice_id").asText(),
                root.path("document_type").asText(),
                ocr,
                find(ocr, "Reference No:\\s*(\\S+)"),
                find(ocr, "\\nDate:\\s*(\\d{2}-\\d{2}-\\d{4})"),
                find(ocr, "To,\\s*\\n(M/s [^\\n]+)"),
                gstin,
                Objects.requireNonNull(STATE_BY_CODE.get(gstin.substring(0, 2)), "state code " + gstin.substring(0, 2)),
                find(ocr, "Financial Year:\\s*(\\S+)"),
                find(ocr, "(?:Show Cause Notice|Notice) under (Section \\d+[A-Z]?)"),
                "Within " + find(ocr, "(?i)within (\\d+ days from \\w+)"),
                root.path("answer_key").path("para_count").asInt(),
                root.path("answer_key").path("issues"),
                total);
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), "pattern not found in notice JSON: " + regex);
        return m.group(1).trim();
    }

    private static String inr(double amount) {
        return TemplateFillService.formatIndianCurrency(BigDecimal.valueOf(amount));
    }

    private static String section(DraftingPipelineResult r, int num) {
        return r.draft().sections().stream().filter(s -> s.num() == num).findFirst().orElseThrow().bodyHtml();
    }

    private static String clientFacing(DraftingPipelineResult r) {
        StringBuilder sb = new StringBuilder();
        r.draft().sections().stream().filter(s -> s.num() != 13).forEach(s -> sb.append(s.bodyHtml()).append('\n'));
        return sb.toString();
    }

    private static DraftCheckResult check(DraftingPipelineResult r, String name) {
        return r.checkResults().stream().filter(c -> c.checkName().equals(name)).findFirst().orElseThrow();
    }

    private static void compareOrUpdate(String fileName, String actual) throws IOException {
        Path file = GOLDEN_DIR.resolve(fileName);
        if (Boolean.getBoolean("golden.update") || !Files.exists(file)) {
            Files.createDirectories(GOLDEN_DIR);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        assertEquals(Files.readString(file, StandardCharsets.UTF_8), actual,
                "Golden draft " + fileName + " changed; review it and regenerate with -Dgolden.update=true");
    }

    private static String render(String header, DraftingPipelineResult result) {
        StringBuilder sb = new StringBuilder(header).append("\n\n");
        for (DraftSection sec : result.draft().sections()) {
            sb.append("================================================================================\n");
            sb.append("SECTION ").append(sec.num()).append(": ").append(sec.title()).append("\n");
            sb.append("================================================================================\n");
            sb.append(htmlToText(sec.bodyHtml())).append("\n\n");
        }
        return sb.toString();
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        return html.replaceAll("<!--[^>]*-->", "")
                   .replace("<p>", "").replace("</p>", "\n")
                   .replace("<h3>", "\n--- ").replace("</h3>", " ---\n")
                   .replaceAll("<ul>|</ul>|<ol>|</ol>", "")
                   .replace("<li>", "  * ").replace("</li>", "\n")
                   .replaceAll("<table>|</table>", "")
                   .replace("<tr>", "").replace("</tr>", "\n")
                   .replace("<th>", "").replace("</th>", " | ")
                   .replace("<td>", "").replace("</td>", " | ")
                   .replace(" | \n", "\n")
                   .replaceAll("<[^>]+>", "");
    }
}
