package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.*;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialSectionAddition;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.DraftingPipelineResult;
import com.noticedesk.api.service.V7DraftingPipelineService;
import com.noticedesk.api.util.HtmlSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class V7Stage3GoldenDraftTest {

    private TemplateFillService templateFillService;
    private ReplyAssemblyService replyAssemblyService;
    private DraftCheckService draftCheckService;
    private IssueMatchingAgent mockMatchingAgent;
    private PartialDraftingAgent mockPartialAgent;
    private NewIssueDraftingAgent mockNewIssueAgent;
    private HighStakesDraftingAgent mockHighStakesAgent;
    private CitationVerificationAgent mockCitationAgent;
    private V7DraftingPipelineService v7PipelineService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        templateFillService = new TemplateFillService(null, null, objectMapper);
        replyAssemblyService = new ReplyAssemblyService(null, null, objectMapper);
        draftCheckService = new DraftCheckService(null);
        mockMatchingAgent = Mockito.mock(IssueMatchingAgent.class);
        mockPartialAgent = Mockito.mock(PartialDraftingAgent.class);
        mockNewIssueAgent = Mockito.mock(NewIssueDraftingAgent.class);
        mockHighStakesAgent = Mockito.mock(HighStakesDraftingAgent.class);
        mockCitationAgent = Mockito.mock(CitationVerificationAgent.class);

        v7PipelineService = new V7DraftingPipelineService(
                mockMatchingAgent, null, objectMapper,
                templateFillService, replyAssemblyService, draftCheckService,
                mockPartialAgent, mockNewIssueAgent, mockHighStakesAgent, mockCitationAgent
        );

        // Register dev template for CARD-006
        ReplyTemplate tpl006 = new ReplyTemplate(
                "TPL-006", 1, "CARD-006",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                List.of(
                        new TemplateBlock("BLK-006-04A", 4, "<p>[DEV - not CA reviewed] It is submitted that the discrepancy of {{issue.amount_tax}} in GSTR-2B was due to {{issue.reason_for_difference}}, and {{issue.section16_conditions_statement}} by {{client.legal_name}}.</p>", List.of("CIT-001"), null),
                        new TemplateBlock("BLK-006-06A", 6, "<p>[DEV - not CA reviewed] Section 16(2)(aa) cannot be applied retroactively or punitively where genuine tax has been deposited by the supplier into the public exchequer.</p>", List.of("CIT-001"), null)
                ),
                List.of("issue.amount_tax", "notice.din", "client.legal_name", "issue.reason_for_difference", "issue.section16_conditions_statement"),
                List.of(), "Input Tax Credit disallowance under Section 16(2)(aa) of {{issue.amount_tax}}.", List.of(), null, null, null
        );
        templateFillService.registerInMemoryTemplate(tpl006);

        StageTemplate stageScn73 = new StageTemplate(
                "scn_73", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                Map.of(
                        "08", "<p>[DEV - not CA reviewed] Request for cross-examination of departmental audit officers or third parties whose material is relied upon.</p>",
                        "12", "<p>[DEV - not CA reviewed] PRAYER: It is humbly prayed that the Show Cause Notice issued under Section 73 be dropped in full and no penalty or interest be levied. It is further requested that an opportunity of personal hearing be granted before passing any adverse order as mandated under Section 75(4) of the CGST Act, 2017.</p>",
                        "14", "<p>[DEV - not CA reviewed] CLIENT SUMMARY: Show Cause Notice under Section 73 demanding tax, interest, and penalty. Reply required before due date.</p>",
                        "15", "<p>[DEV - not CA reviewed] FILING CHECKLIST: 1. Written Submissions 2. Form GST DRC-06 3. Authorisation Letter 4. Document Annexures.</p>"
                ),
                null, null, null
        );
        replyAssemblyService.registerInMemoryStageTemplate(stageScn73);
    }

    @Test
    void testNotice07PartialMatchGoldenDraft() throws IOException {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("SCN/MH/2023-24/707", "DIN-2023-0707", "10-10-2023", "Within 30 days", "2022-23", "2022-23", 250000.0, "State Tax Officer");
        MatchedIssue issue1 = new MatchedIssue(1, "partial", List.of("CARD-006"), "GSTR-3B vs 2B mismatch with custom supplier dispute", List.of(), Map.of("period", "2022-23", "amount", 250000.0), List.of(), List.of());

        MatchingResult matchingResult = new MatchingResult(
                noticeInfo, List.of(issue1),
                Map.of("record", List.of(1), "issue", List.of(2), "demand", List.of(3), "directions", List.of(4)),
                List.of(), List.of(), "hash-07", 100, 100, 0, 0
        );

        Mockito.when(mockMatchingAgent.matchNoticeWithOcrText(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(matchingResult);

        // Mock PartialDraftingAgent Sonnet output
        PartialDraftingResult partialAiRes = new PartialDraftingResult(
                List.of(
                        new PartialSectionAddition(4, "BLK-006-04A", "<p>[Sonnet AI] Additional custom defence regarding supplier GSTIN cancellation taking retrospective effect after transaction completion.</p>"),
                        new PartialSectionAddition(6, "BLK-006-06A", "<p>[Sonnet AI] Precedent: State of Maharashtra v. Suresh Trading Co. (1998) 109 STC 439 (SC) — purchasing dealer cannot be penalized for subsequent cancellation of seller's registration.</p>")
                ),
                List.of(new RawAiCitation("State of Maharashtra v. Suresh Trading Co.", "Supreme Court", 1998, "purchasing dealer cannot be penalized", "Buyer protection"))
        );
        Mockito.when(mockPartialAgent.generatePartialDrafting(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(partialAiRes);

        DraftingInput input = new DraftingInput(
                UUID.randomUUID(), "formal", "Submit strong defence", "M/s Apex Freight Systems Pvt Ltd", "IIIJJ1234I", "Company", "Regular", "27IIIJJ1234I1Z9", "Maharashtra", "CGST Act", "2022-23", "2023-24",
                Map.of("notice_id", "notice-bench-007", "document_type", "scn_73", "notice_number", "SCN/MH/2023-24/707", "issue_date", "10-10-2023", "due_date", "Within 30 days", "section", "Section 73", "total_demand_amount", 250000.0), Map.of(), "OCR Text 07", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null
        );

        DraftingPipelineResult result = v7PipelineService.runPipeline(input);

        assertNotNull(result);
        String fullText = renderGoldenSnapshotText("=== NOTICE 07 V7 STAGE 3 GOLDEN DRAFT SNAPSHOT (PARTIAL MATCH) ===", result);

        saveSnapshotFile("notice_07_v7_assembled_golden.txt", fullText);
        System.out.println(fullText);
    }

    @Test
    void testNotice10NoMatchGoldenDraft() throws IOException {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("SCN/MH/2023-24/1010", "DIN-2023-1010", "15-10-2023", "Within 30 days", "2022-23", "2022-23", 500000.0, "State Tax Officer");
        MatchedIssue issue1 = new MatchedIssue(1, "none", List.of(), "Novel GST classification dispute on transport agency services", List.of(), Map.of("period", "2022-23", "amount", 500000.0), List.of(), List.of());

        MatchingResult matchingResult = new MatchingResult(
                noticeInfo, List.of(issue1),
                Map.of("record", List.of(1), "issue", List.of(2), "demand", List.of(3), "directions", List.of(4)),
                List.of(), List.of(), "hash-10", 100, 100, 0, 0
        );

        Mockito.when(mockMatchingAgent.matchNoticeWithOcrText(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(matchingResult);

        // Mock NewIssueDraftingAgent Opus output
        NewIssueDraftingResult newIssueRes = new NewIssueDraftingResult(
                1,
                "<p>[Opus AI] It is submitted that transport management services provided by taxpayer qualify as Goods Transport Agency (GTA) services under SAC 9965, subject to RCM or 5% GST option.</p>",
                "<li><strong>Para 2:</strong> Denied. The service is wrongly classified under support services SAC 9985.</li>",
                "<p>[Opus AI] Principles of classification under Section 8 of CGST Act mandate predominant character assessment. Reference: Commissioner of Central Excise v. Menon Pistons Ltd. (2016) 333 ELT 3 (SC).</p>",
                List.of(new RawAiCitation("Commissioner of Central Excise v. Menon Pistons Ltd.", "Supreme Court", 2016, "predominant character assessment", "Classification principles")),
                "Opus Partner Note: Novel classification issue. Ensure consignment notes / B/Ls are attached as evidence."
        );
        Mockito.when(mockNewIssueAgent.generateNewIssueDraft(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(newIssueRes);

        DraftingInput input = new DraftingInput(
                UUID.randomUUID(), "formal", "Submit strong defence", "M/s Oceanline Logistics India Pvt Ltd", "IIIJJ1234I", "Company", "Regular", "27IIIJJ1234I1Z9", "Maharashtra", "CGST Act", "2022-23", "2023-24",
                Map.of("notice_id", "notice-bench-010", "document_type", "scn_73", "notice_number", "SCN/MH/2023-24/1010", "issue_date", "15-10-2023", "due_date", "Within 30 days", "section", "Section 73", "total_demand_amount", 500000.0), Map.of(), "OCR Text 10", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null
        );

        DraftingPipelineResult result = v7PipelineService.runPipeline(input);

        assertNotNull(result);
        String fullText = renderGoldenSnapshotText("=== NOTICE 10 V7 STAGE 3 GOLDEN DRAFT SNAPSHOT (NO MATCH / NOVEL ISSUE) ===", result);

        saveSnapshotFile("notice_10_v7_assembled_golden.txt", fullText);
        System.out.println(fullText);
    }

    private String renderGoldenSnapshotText(String header, DraftingPipelineResult result) {
        StringBuilder fullText = new StringBuilder();
        fullText.append(header).append("\n\n");

        for (DraftSection sec : result.draft().sections()) {
            fullText.append("================================================================================\n");
            fullText.append("SECTION ").append(sec.num()).append(": ").append(sec.title()).append("\n");
            fullText.append("================================================================================\n");
            fullText.append(cleanHtmlTags(sec.bodyHtml())).append("\n\n");
        }

        return fullText.toString();
    }

    private void saveSnapshotFile(String fileName, String content) throws IOException {
        File goldenDir = new File("src/test/resources/testbench/golden");
        if (!goldenDir.exists()) {
            goldenDir.mkdirs();
        }
        File snapshotFile = new File(goldenDir, fileName);
        try (FileWriter writer = new FileWriter(snapshotFile, StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    private String cleanHtmlTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<p>", "")
                   .replaceAll("</p>", "\n")
                   .replaceAll("<h3>", "\n--- ")
                   .replaceAll("</h3>", " ---\n")
                   .replaceAll("<ul>|</ul>|<ol>|</ol>", "")
                   .replaceAll("<li>", "  * ")
                   .replaceAll("</li>", "\n")
                   .replaceAll("<table>|</table>|<thead>|<tbody>|</thead>|</tbody>", "")
                   .replaceAll("<tr>", "")
                   .replaceAll("</tr>", "\n")
                   .replaceAll("<th>", "")
                   .replaceAll("</th>", " | ")
                   .replaceAll("<td>", "")
                   .replaceAll("</td>", " | ")
                   .replaceAll("<!--[^>]*-->", "")
                   .replaceAll(" \\| \n", "\n")
                   .replaceAll("<[^>]+>", "");
    }
}
