package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent.AiIssueSectionRecord;
import com.noticedesk.api.agent.NewIssueDraftingAgent.NewIssueDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialDraftingResult;
import com.noticedesk.api.agent.PartialDraftingAgent.PartialSectionAddition;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.ReplyTemplate;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.model.matching.TemplateBlock;
import com.noticedesk.api.service.learned.LearnedEntry;
import com.noticedesk.api.service.learned.LearnedLibrary;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The v7 pipeline saves every new-issue writer output for an issue with no catalogue card to the learned library
 * (provisional), mirrors the entry id / run in ai_issue_sections, and leaves template-filled and partial issues alone.
 * Every external call is mocked.
 */
class V7LearnedLibraryPipelineTest {

    @TempDir
    Path privateDir;

    private final ObjectMapper om = new ObjectMapper();
    private AppProperties props;
    private LearnedLibrary library;
    private V7DraftingPipelineService pipeline;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setPrivateSeedDir(privateDir.toString());
        library = new LearnedLibrary(props, om);
        library.setSourceRun("feedbee1");

        TemplateFillService fill = new TemplateFillService(null, null, om);
        ReplyAssemblyService assembly = new ReplyAssemblyService(null, null, om);
        IssueMatchingAgent matchingAgent = mock(IssueMatchingAgent.class);
        PartialDraftingAgent partialAgent = mock(PartialDraftingAgent.class);
        NewIssueDraftingAgent newIssueAgent = mock(NewIssueDraftingAgent.class);
        HighStakesDraftingAgent highStakesAgent = mock(HighStakesDraftingAgent.class);
        pipeline = new V7DraftingPipelineService(matchingAgent, null, om, fill, assembly, new DraftCheckService(null),
                partialAgent, newIssueAgent, highStakesAgent, null, null, props, null);
        pipeline.setLearnedLibrary(library);

        fill.registerInMemoryTemplate(new ReplyTemplate("TPL-A", 1, "CARD-A",
                LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "active",
                List.of(new TemplateBlock("BLK-A-04", 4, "<p>Template ground on {{issue.amount_tax}}.</p>", List.of(), null)),
                List.of("issue.amount_tax"), List.of("Template document A"), "Template summary.", List.of(), null, null, null));
        assembly.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, null, null, "active",
                Map.of("12", "<p>Prayer.</p>", "15", "<p>Checklist.</p>"), null, null, null));

        MatchedNoticeInfo info = new MatchedNoticeInfo("REF/1", "DIN-1", "01-04-2024", "within 30 days", "2022-23", "2022-23", 160000.0, "Officer");
        MatchingResult mr = new MatchingResult(info, List.of(
                new MatchedIssue(1, "full", List.of("CARD-A"), "w", List.of(), Map.of("period", "2022-23", "amount", 100000.0), List.of("2"), List.of()),
                new MatchedIssue(2, "partial", List.of("CARD-B"), "w", List.of(), Map.of("period", "2022-23", "amount", 50000.0), List.of("3"), List.of()),
                new MatchedIssue(3, "none", List.of(), "ITC on hire charges denied", List.of(),
                        Map.of("period", "2022-23", "amount", 10000.0, "legal_section", "Section 17(5)"), List.of("4"), List.of())),
                Map.of("record", List.of(1), "issue", List.of(2, 3, 4), "demand", List.of(5)),
                List.of(), List.of(), "h", 1, 1, 0, 0);
        when(matchingAgent.matchNoticeWithOcrText(anyString(), anyString(), any(), any(), any())).thenReturn(mr);
        when(partialAgent.generatePartialDrafting(any(), any(), any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new PartialDraftingResult(List.of(new PartialSectionAddition(4, null, "<p>Partial ground.</p>")),
                        List.of(), List.of("Partial document"), "Partial summary.", "test-model-partial", "partial_drafting_v2"));
        when(newIssueAgent.generateNewIssueDraft(any(), any(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new NewIssueDraftingResult(3, "<p>Hire of machinery is not a blocked credit under Section 17(5).</p>",
                        "<li><strong>Para 4:</strong> Denied.</li>", "<p>Section 16(2) conditions are met.</p>",
                        List.of(), "Moderate.", List.of("Hire agreements"), "New issue summary.", "test-model-new", "new_issue_v2"));
    }

    private DraftingInput input() {
        return new DraftingInput(UUID.randomUUID(), "formal", null, "Test Client Pvt Ltd", null, null, null,
                "TESTGSTIN", "Test State", "CGST Act", "2022-23", null,
                Map.of("notice_id", "pdf_notice_01", "document_type", "DRC-01", "section", "Section 73"), Map.of(),
                "FORM GST DRC-01 notice text", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
    }

    @Test
    void newIssueWriterOutputForAnUnmatchedIssueIsSavedAsAProvisionalEntry() {
        DraftingPipelineResult r = pipeline.runPipeline(input());

        assertEquals(List.of("Learned: saved " + library.entries().get(0).entryId() + " for issue #3"), V7LiveRig.learnedLines(r));
        assertEquals(1, library.counts().total(), "only the new-issue writer's issue is saved (not the partial one)");
        LearnedEntry e = library.entries().get(0);
        assertEquals("pdf_notice_01", e.noticeId());
        assertEquals("DRC-01", e.documentType());
        assertEquals("reply", e.stage(), "response stage of a DRC-01");
        assertEquals("ITC on hire charges denied", e.ground());
        assertEquals("Section 17(5)", e.legalSection());
        assertEquals(props.getLlm().providerForAgent("new_issue") + ":test-model-new", e.writer(), "provider:model of the writer");
        assertEquals("provisional", e.status());
        assertEquals("feedbee1", e.sourceRun());
        assertEquals("Test Client Pvt Ltd", e.noticeFacts().get("client_name"));

        AiIssueSectionRecord row = r.aiIssueSections().stream().filter(x -> x.issueNo() == 3).findFirst().orElseThrow();
        assertEquals(e.entryId(), row.outputJson().get(V7DraftingPipelineService.OUTPUT_LEARNED_ENTRY_ID));
        assertEquals("feedbee1", row.outputJson().get(V7DraftingPipelineService.OUTPUT_LEARNED_SOURCE_RUN));
        assertEquals("provisional", row.outputJson().get("learned_status"));
        assertEquals("pending_review", row.status(), "the ai_issue_sections status keeps its meaning");
        AiIssueSectionRecord partialRow = r.aiIssueSections().stream().filter(x -> x.issueNo() == 2).findFirst().orElseThrow();
        assertNull(partialRow.outputJson().get(V7DraftingPipelineService.OUTPUT_LEARNED_ENTRY_ID));
    }

    @Test
    void nothingIsSavedWithoutPrivateSeedDirAndTheDraftIsUnchanged() {
        props.setPrivateSeedDir("");
        DraftingPipelineResult r = pipeline.runPipeline(input());
        assertEquals(List.of(), V7LiveRig.learnedLines(r));
        assertEquals(2, r.aiIssueSections().size());
        assertTrue(r.draft().sections().stream().anyMatch(s -> s.bodyHtml().contains("not a blocked credit")));
    }

    @Test
    void entryIdAndRunAreMirroredInTheAiIssueSectionsRow() {
        DraftingPipelineResult r = pipeline.runPipeline(input());
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        V7DraftingPipelineService persisting = new V7DraftingPipelineService(mock(IssueMatchingAgent.class), jdbc, om,
                new TemplateFillService(null, null, om), new ReplyAssemblyService(null, null, om), new DraftCheckService(null),
                null, null, null, null, null, props, null);
        persisting.persistAiIssueSections(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), r.aiIssueSections());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(sql.capture(), params.capture());
        assertTrue(sql.getValue().contains("entry_id, source_run"));
        Map<Integer, MapSqlParameterSource> byIssue = new HashMap<>();
        for (SqlParameterSource p : params.getAllValues()) byIssue.put((Integer) p.getValue("issueNo"), (MapSqlParameterSource) p);
        assertEquals(library.entries().get(0).entryId(), byIssue.get(3).getValue("entryId"));
        assertEquals("feedbee1", byIssue.get(3).getValue("sourceRun"));
        assertNull(byIssue.get(2).getValue("entryId"));
    }
}
