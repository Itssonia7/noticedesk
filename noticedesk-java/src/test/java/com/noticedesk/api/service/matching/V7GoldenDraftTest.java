package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.DraftingPipelineResult;
import com.noticedesk.api.service.V7DraftingPipelineService;
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

class V7GoldenDraftTest {

    private TemplateFillService templateFillService;
    private ReplyAssemblyService replyAssemblyService;
    private DraftCheckService draftCheckService;
    private IssueMatchingAgent mockMatchingAgent;
    private V7DraftingPipelineService v7PipelineService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        templateFillService = new TemplateFillService(null, null, objectMapper);
        replyAssemblyService = new ReplyAssemblyService(null, null, objectMapper);
        draftCheckService = new DraftCheckService(null);
        mockMatchingAgent = Mockito.mock(IssueMatchingAgent.class);

        v7PipelineService = new V7DraftingPipelineService(
                mockMatchingAgent, null, objectMapper,
                templateFillService, replyAssemblyService, draftCheckService
        );

        // Register dev templates for CARD-006 and CARD-011
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

        ReplyTemplate tpl011 = new ReplyTemplate(
                "TPL-011", 1, "CARD-011",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                List.of(
                        new TemplateBlock("BLK-011-04A", 4, "<p>[DEV - not CA reviewed] The interest demand of {{issue.amount_tax}} under Section 50 has been computed on gross tax liability instead of net cash liability. As per proviso to Section 50(1), interest is payable only on cash ledger debit.</p>", List.of("CIT-002"), null),
                        new TemplateBlock("BLK-011-06A", 6, "<p>[DEV - not CA reviewed] Proviso to Section 50(1) inserted retrospectively from 01-07-2017 mandates calculation of interest solely on net cash tax liability.</p>", List.of("CIT-002"), null)
                ),
                List.of("issue.amount_tax", "notice.din", "client.legal_name"),
                List.of(), "Interest demand under Section 50 of {{issue.amount_tax}} on gross tax liability.", List.of(), null, null, null
        );

        templateFillService.registerInMemoryTemplate(tpl006);
        templateFillService.registerInMemoryTemplate(tpl011);

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
    void testGoldenDraftAssemblyForNotice06() throws IOException {
        MatchedNoticeInfo noticeInfo = new MatchedNoticeInfo("SCN/MH/2023-24/606", "DIN-2023-0606", "18-09-2023", "Within 30 days", "2022-23", "2022-23", 155918.0, "State Tax Officer");
        MatchedIssue issue1 = new MatchedIssue(1, "full", List.of("CARD-006"), "GSTR-3B vs 2B mismatch", List.of(), Map.of("period", "2022-23", "amount", 150000.0), List.of(), List.of());
        MatchedIssue issue2 = new MatchedIssue(2, "full", List.of("CARD-011"), "Section 50 interest on gross tax liability", List.of(), Map.of("period", "2022-23", "amount", 5918.0), List.of(), List.of());

        MatchingResult matchingResult = new MatchingResult(
                noticeInfo, List.of(issue1, issue2),
                Map.of("record", List.of(1, 4), "issue", List.of(2, 3), "demand", List.of(5), "directions", List.of(6)),
                List.of(), List.of(), "hash-06", 100, 100, 0, 0
        );

        Mockito.when(mockMatchingAgent.matchNoticeWithOcrText(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(matchingResult);

        DraftingInput input = new DraftingInput(
                UUID.randomUUID(), "formal", "Submit strong defence", "M/s Vantage Logistics Pvt Ltd", "IIIJJ1234I", "Company", "Regular", "27IIIJJ1234I1Z9", "Maharashtra", "CGST Act", "2022-23", "2023-24",
                Map.of("notice_id", "notice-bench-006", "document_type", "scn_73", "notice_number", "SCN/MH/2023-24/606", "issue_date", "18-09-2023", "due_date", "Within 30 days", "section", "Section 73", "total_demand_amount", 155918.0), Map.of(), "OCR Text", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null
        );

        DraftingPipelineResult result = v7PipelineService.runPipeline(input);

        assertNotNull(result);
        assertNotNull(result.draft());
        assertEquals(15, result.draft().sections().size());

        StringBuilder fullText = new StringBuilder();
        fullText.append("=== NOTICE 06 V7 ASSEMBLED GOLDEN DRAFT SNAPSHOT ===\n\n");

        for (DraftSection sec : result.draft().sections()) {
            fullText.append("================================================================================\n");
            fullText.append("SECTION ").append(sec.num()).append(": ").append(sec.title()).append("\n");
            fullText.append("================================================================================\n");
            fullText.append(cleanHtmlTags(sec.bodyHtml())).append("\n\n");
        }

        // Save to snapshot file
        File goldenDir = new File("src/test/resources/testbench/golden");
        if (!goldenDir.exists()) {
            goldenDir.mkdirs();
        }
        File snapshotFile = new File(goldenDir, "notice_06_v7_assembled_golden.txt");
        try (FileWriter writer = new FileWriter(snapshotFile, StandardCharsets.UTF_8)) {
            writer.write(fullText.toString());
        }

        assertTrue(snapshotFile.exists());
        assertTrue(snapshotFile.length() > 500);

        System.out.println(fullText.toString());
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

