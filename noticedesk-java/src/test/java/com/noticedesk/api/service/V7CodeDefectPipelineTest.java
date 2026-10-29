package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftSection;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.agent.TemplateVariableAgent;
import com.noticedesk.api.agent.TemplateVariableAgent.IssueVariableRequest;
import com.noticedesk.api.agent.TemplateVariableAgent.TemplateVariablesResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.matching.CodeDefectMerger;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The code defect checks inside the full v7 pipeline (every external call mocked): a time-barred Section 73 SCN of
 * FY 2019-20 (no card for that regime) and a missing DIN on a central-tax SCN are added as defect issues after the
 * demand issue; the matcher's signature defect on a digitally signed document is kept and disputed. The variable
 * step, template fill, Section 07 and the flag lists then work on the merged, renumbered issues.
 */
class V7CodeDefectPipelineTest {

    private static final String TEXT = """
            GOVERNMENT OF INDIA
            OFFICE OF THE SUPERINTENDENT OF CENTRAL TAX, RANGE-IV
            DIN: [____]
            Show Cause Notice under Section 73 of the CGST Act, 2017
            Reference No: SCN/CT/2024-25/9
            Date: 02.06.2024

            To,
            M/s Test Client Pvt Ltd
            Financial Year: 2019-20

            1. WHEREAS returns for FY 2019-20 were scrutinised.
            2. Input tax credit of Rs. 1,00,000/- is proposed to be recovered under Section 73(1).
            3. Reply within 30 days.

            Digitally signed by RAJESH KUMAR
            Superintendent, CGST Range-IV
            """;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void codeDefectsAreMergedRenumberedFilledRenderedInSection07AndFlagged() {
        TemplateFillService fill = new TemplateFillService(null, null, om);
        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        fill.registerInMemoryTemplate(new ReplyTemplate("TPL-A", 1, "CARD-A",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "active",
                List.of(new TemplateBlock("BLK-A-04", 4, "<p>Template ground for {{client.legal_name}}.</p>", List.of(), null)),
                List.of("client.legal_name"), List.of("Doc A"), "Template A summary.", List.of(), null, null, null));
        fill.registerInMemoryTemplate(new ReplyTemplate("TPL-141", 1, "NDK-141",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "active",
                List.of(new TemplateBlock("BLK-141-04", 4, "<p>The notice carries no DIN, contrary to {{issue.circular_reference}}.</p>", List.of(), null)),
                List.of("issue.circular_reference"), List.of("Copy of the notice"), "No DIN.", List.of(), null, null, null));
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, null, null, "active",
                Map.of("12", "<p>Prayer: drop the proceedings under {{notice.section}}.</p>", "15", "<p>Checklist.</p>"), null, null, null));

        IssueMatchingAgent matcher = mock(IssueMatchingAgent.class);
        MatchedNoticeInfo info = new MatchedNoticeInfo("SCN/CT/2024-25/9", null, "02.06.2024", "within 30 days", "2019-20", "2019-20",
                100000.0, "Superintendent of Central Tax");
        when(matcher.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(new MatchingResult(info, List.of(
                new MatchedIssue(1, "full", "demand", List.of("CARD-A"), List.of(), "w", List.of(),
                        Map.of("period", "2019-20", "amount", 100000.0), List.of("2"), List.of(), List.of(), null),
                // the matcher's signature defect: the document is digitally signed
                new MatchedIssue(2, "full", "defect", List.of("NDK-143"), List.of(), "unsigned", List.of(),
                        Map.of("period", "2019-20"), List.of("header"), List.of(), List.of(), null)),
                Map.of("2", List.of(1)), List.of(), List.of(), "h", 1, 1, 0, 0));

        NewIssueDraftingAgent newIssue = mock(NewIssueDraftingAgent.class);
        when(newIssue.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new NewIssueDraftingResult(2, "<p>Signature objection.</p>", null, null, List.of(), null,
                        List.of(), "Signature.", "test-model", "new_issue_v2"));
        TemplateVariableAgent variables = mock(TemplateVariableAgent.class);
        when(variables.extractVariables(anyString(), anyList(), any(), any())).thenReturn(new TemplateVariablesResult(
                Map.of(4, Map.of("issue.circular_reference", "CBIC Circular 122/41/2019-GST")), List.of(), "test-model",
                TemplateVariableAgent.PROMPT_VERSION));
        HighStakesDraftingAgent highStakes = mock(HighStakesDraftingAgent.class);

        V7DraftingPipelineService pipeline = new V7DraftingPipelineService(matcher, null, om, fill, assembly, new DraftCheckService(null),
                mock(PartialDraftingAgent.class), newIssue, highStakes, null, variables);
        DraftingInput input = new DraftingInput(UUID.randomUUID(), "formal", null, "Test Client Pvt Ltd", null, null, null,
                "TESTGSTIN", "Test State", "CGST Act", "2019-20", null,
                Map.of("document_type", "scn_73", "section", "Section 73"), Map.of(), TEXT, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, null);

        DraftingPipelineResult r = pipeline.runPipeline(input);

        // the variable step asked for the template variable of the added DIN issue, under its new number (#4)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IssueVariableRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(variables).extractVariables(anyString(), requests.capture(), any(), any());
        assertEquals(List.of(4), requests.getValue().stream().map(IssueVariableRequest::issueNo).toList());
        assertEquals("NDK-141", requests.getValue().get(0).cardId());

        // only the matcher's signature issue goes to a writer: the limitation issue has no card, the DIN issue a template
        verify(newIssue, times(1)).generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any());
        verify(newIssue).generateNewIssueDraft(argThat(i -> i.issueNo() == 2 && "NDK-143".equals(i.primaryCardId())),
                any(), anyString(), anyString(), any(), anyBoolean(), any());

        String s04 = section(r, 4);
        assertTrue(s04.contains("Template ground for Test Client Pvt Ltd."), s04);
        assertFalse(s04.contains("DIN"), s04);
        String s07 = section(r, 7);
        assertTrue(s07.contains("Issue #2 Procedural Objection"), s07);
        assertTrue(s07.contains("Issue #3 Procedural Objection"), s07);
        assertTrue(s07.contains("[[PARTNER: SCN 02.06.2024 vs Section 73 SCN deadline 31.05.2024 for FY 2019-20: time-barred;"
                + " no catalogue card for this regime]]"), s07);
        assertTrue(s07.contains("Issue #4 Procedural Objection"), s07);
        assertTrue(s07.contains("The notice carries no DIN, contrary to CBIC Circular 122/41/2019-GST."), s07);
        assertTrue(s07.indexOf("Issue #3") < s07.indexOf("Issue #4"));

        // Section 13: each issue's line carries its flags
        String s13 = section(r, 13);
        assertTrue(s13.contains("Issue #2: Match Status=full, Cards=[NDK-143]"), s13);
        assertTrue(line(s13, "Issue #2:").contains("Issue flags=[DEFECT_DISPUTED_BY_CODE:warn]"), s13);
        assertTrue(line(s13, "Issue #2:").contains("code check finds no defect (signature evidence: Digitally signed by RAJESH KUMAR)"), s13);
        assertTrue(line(s13, "Issue #3:").contains("Match Status=none, Cards=[]"), s13);
        assertTrue(line(s13, "Issue #3:").contains("Issue flags=[DEFECT_ADDED_BY_CODE:info]"), s13);
        assertTrue(line(s13, "Issue #4:").contains("Cards=[NDK-141], Template=TPL-141 (v1), Written by=template (code)"), s13);
        assertTrue(line(s13, "Issue #4:").contains("Issue flags=[DEFECT_ADDED_BY_CODE:info]"), s13);
        assertTrue(s13.contains("Code defect checks: limitation_73=defect"), s13);
        assertTrue(s13.contains("Issue #3: defect added by code check limitation_73"), s13);

        // the draft's flag list, as the live runner prints it
        List<String> flagLine = V7LivePipelineRunnerTest.flagList(r.checkResults());
        assertTrue(flagLine.contains("DEFECT_ADDED_BY_CODE#3:info"), flagLine.toString());
        assertTrue(flagLine.contains("DEFECT_ADDED_BY_CODE#4:info"), flagLine.toString());
        assertTrue(flagLine.contains("DEFECT_DISPUTED_BY_CODE#2:warn"), flagLine.toString());
        DraftCheckResult added = r.checkResults().stream().filter(c -> c.checkName().equals("DEFECT_ADDED_BY_CODE#4")).findFirst().orElseThrow();
        assertEquals(4, added.details().get("issue_no"));
        assertEquals(CodeDefectMerger.FLAG_ADDED, added.details().get("flag"));
        assertTrue(r.checkResults().stream().filter(c -> c.checkName().equals("CHECK_EVERY_ISSUE_ANSWERED")).findFirst().orElseThrow().passed());
    }

    @Test
    void stageFlagsAppearOnTheFlagLineWithTheIssueNumber() {
        MatchedIssue corrected = new MatchedIssue(3, "full", "demand", List.of("NDK-224"), List.of("NDK-225"), "w", List.of(),
                Map.of(), List.of(), List.of("stage_corrected: NDK-225 -> NDK-224", "hedged"), List.of(), null);
        MatchedIssue mismatch = new MatchedIssue(4, "full", "demand", List.of("NDK-221"), List.of(), "w", List.of(),
                Map.of(), List.of(), List.of("stage_mismatch: appeal vs reply"), List.of(), null);
        List<String> line = V7LivePipelineRunnerTest.flagList(
                com.noticedesk.api.service.matching.IssueFlags.checkResults(List.of(corrected, mismatch)));
        assertEquals(List.of("STAGE_CORRECTED#3:info", "STAGE_MISMATCH#4:warn"), line);
    }

    private static String section(DraftingPipelineResult r, int num) {
        return r.draft().sections().stream().filter(s -> s.num() == num).map(DraftSection::bodyHtml).findFirst().orElseThrow();
    }

    /** The Section 13 list item that starts with {@code prefix}. */
    private static String line(String s13, String prefix) {
        int at = s13.indexOf("<li>" + prefix);
        assertTrue(at >= 0, prefix + " not in " + s13);
        return s13.substring(at, s13.indexOf("</li>", at));
    }
}
