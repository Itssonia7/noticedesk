package com.noticedesk.api.workflow;

import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.DraftingPipelineResult;
import com.noticedesk.api.service.DraftingPipelineService;
import com.noticedesk.api.service.GstCorpusMatcherService;
import com.noticedesk.api.service.GstTemplateFillerService;
import com.noticedesk.api.service.V7DraftingPipelineService;
import com.noticedesk.api.service.rag.RagStoreService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class PipelineSwitchTest {

    @Test
    void testRagPipelineSelection() {
        AppProperties props = new AppProperties();
        props.getDrafting().setPipeline("rag");

        DraftingPipelineService ragService = Mockito.mock(DraftingPipelineService.class);
        V7DraftingPipelineService v7Service = Mockito.mock(V7DraftingPipelineService.class);

        DraftingWorkflow workflow = new DraftingWorkflow(
                null, Mockito.mock(DraftingAgent.class), Mockito.mock(CitationVerificationAgent.class),
                Mockito.mock(AuditService.class), props, null,
                Mockito.mock(GstCorpusMatcherService.class), Mockito.mock(GstTemplateFillerService.class),
                Mockito.mock(RagStoreService.class), ragService, v7Service
        );

        assertDoesNotThrow(workflow::validatePipelineConfig);
        assertEquals("rag", props.getDrafting().getPipeline());
    }

    @Test
    void testV7PipelineSelectionExecutesV7Service() {
        AppProperties props = new AppProperties();
        props.getDrafting().setPipeline("v7");

        V7DraftingPipelineService realV7Service = new V7DraftingPipelineService(
                Mockito.mock(IssueMatchingAgent.class),
                Mockito.mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                Mockito.mock(com.noticedesk.api.service.matching.TemplateFillService.class),
                Mockito.mock(com.noticedesk.api.service.matching.ReplyAssemblyService.class),
                Mockito.mock(com.noticedesk.api.service.matching.DraftCheckService.class)
        );

        DraftingWorkflow workflow = new DraftingWorkflow(
                null, Mockito.mock(DraftingAgent.class), Mockito.mock(CitationVerificationAgent.class),
                Mockito.mock(AuditService.class), props, null,
                Mockito.mock(GstCorpusMatcherService.class), Mockito.mock(GstTemplateFillerService.class),
                Mockito.mock(RagStoreService.class), Mockito.mock(DraftingPipelineService.class), realV7Service
        );

        assertDoesNotThrow(workflow::validatePipelineConfig);
        assertEquals("v7", props.getDrafting().getPipeline());
    }

    @Test
    void testInvalidPipelineConfigFailsStartup() {
        AppProperties props = new AppProperties();
        props.getDrafting().setPipeline("invalid_pipeline_name");

        DraftingWorkflow workflow = new DraftingWorkflow(
                null, Mockito.mock(DraftingAgent.class), Mockito.mock(CitationVerificationAgent.class),
                Mockito.mock(AuditService.class), props, null,
                Mockito.mock(GstCorpusMatcherService.class), Mockito.mock(GstTemplateFillerService.class),
                Mockito.mock(RagStoreService.class), Mockito.mock(DraftingPipelineService.class), Mockito.mock(V7DraftingPipelineService.class)
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, workflow::validatePipelineConfig);
        assertTrue(ex.getMessage().contains("DRAFTING_PIPELINE"));
    }

    @Test
    void testWorkflowDelegatesToCorrectPipelineService() {
        java.util.UUID tenantId = java.util.UUID.randomUUID();
        java.util.UUID userId = java.util.UUID.randomUUID();
        java.util.UUID matterId = java.util.UUID.randomUUID();
        java.util.UUID noticeId = java.util.UUID.randomUUID();

        DraftingWorkflow.GenerateDraftJob job = new DraftingWorkflow.GenerateDraftJob(
                tenantId, userId, matterId, noticeId, "formal", "None", false
        );

        DraftingAgent.DraftingInput mockInput = Mockito.mock(DraftingAgent.DraftingInput.class);
        DraftingAgent mockAgent = Mockito.mock(DraftingAgent.class);
        Mockito.when(mockAgent.loadDraftingInput(Mockito.any(), Mockito.eq(matterId), Mockito.eq(noticeId), Mockito.any(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(mockInput);

        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate mockJdbc =
                Mockito.mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
        Mockito.when(mockJdbc.queryForObject(Mockito.contains("set_config"), Mockito.anyMap(), Mockito.eq(String.class)))
                .thenReturn("ok");
        Mockito.when(mockJdbc.queryForObject(Mockito.contains("MAX(version)"), Mockito.anyMap(), Mockito.eq(Integer.class)))
                .thenReturn(1);
        Mockito.when(mockJdbc.update(Mockito.anyString(), Mockito.anyMap()))
                .thenReturn(1);

        DraftingAgent.GeneratedDraft mockDraft = new DraftingAgent.GeneratedDraft(
                java.util.List.of(new DraftingAgent.DraftSection(1, "Title", "Body")),
                "Note", "Disclaimer", "v1", "haiku", "formal", 10, 10
        );
        DraftingPipelineResult dummyResult = new DraftingPipelineResult(
                mockDraft, java.util.List.of(), java.util.Map.of("total_citations", 0), 0, 0, 0, 10, 10, "end_turn"
        );

        // Scenario 1: pipeline = "rag"
        AppProperties propsRag = new AppProperties();
        propsRag.getDrafting().setPipeline("rag");
        DraftingPipelineService ragService = Mockito.mock(DraftingPipelineService.class);
        V7DraftingPipelineService v7ServiceRag = Mockito.mock(V7DraftingPipelineService.class);
        Mockito.when(ragService.runPipeline(mockInput)).thenReturn(dummyResult);

        DraftingWorkflow workflowRag = new DraftingWorkflow(
                mockJdbc, mockAgent, Mockito.mock(CitationVerificationAgent.class),
                Mockito.mock(AuditService.class), propsRag, new com.fasterxml.jackson.databind.ObjectMapper(),
                Mockito.mock(GstCorpusMatcherService.class), Mockito.mock(GstTemplateFillerService.class),
                Mockito.mock(RagStoreService.class), ragService, v7ServiceRag
        );

        workflowRag.runGenerateDraft(job);
        Mockito.verify(ragService, Mockito.times(1)).runPipeline(mockInput);
        Mockito.verifyNoInteractions(v7ServiceRag);

        // Scenario 2: pipeline = "v7"
        AppProperties propsV7 = new AppProperties();
        propsV7.getDrafting().setPipeline("v7");
        DraftingPipelineService ragServiceV7 = Mockito.mock(DraftingPipelineService.class);
        V7DraftingPipelineService v7Service = Mockito.mock(V7DraftingPipelineService.class);
        Mockito.when(v7Service.runPipeline(mockInput, tenantId, noticeId)).thenReturn(dummyResult);

        DraftingWorkflow workflowV7 = new DraftingWorkflow(
                mockJdbc, mockAgent, Mockito.mock(CitationVerificationAgent.class),
                Mockito.mock(AuditService.class), propsV7, new com.fasterxml.jackson.databind.ObjectMapper(),
                Mockito.mock(GstCorpusMatcherService.class), Mockito.mock(GstTemplateFillerService.class),
                Mockito.mock(RagStoreService.class), ragServiceV7, v7Service
        );

        workflowV7.runGenerateDraft(job);
        Mockito.verify(v7Service, Mockito.times(1)).runPipeline(mockInput, tenantId, noticeId);
        Mockito.verifyNoInteractions(ragServiceV7);
    }
}
