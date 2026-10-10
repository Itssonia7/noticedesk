package com.noticedesk.api.workflow;

import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.DraftingAgent;
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
    void testV7PipelineSelectionThrowsUnsupportedOperation() {
        AppProperties props = new AppProperties();
        props.getDrafting().setPipeline("v7");

        V7DraftingPipelineService realV7Service = new V7DraftingPipelineService();

        DraftingWorkflow workflow = new DraftingWorkflow(
                null, Mockito.mock(DraftingAgent.class), Mockito.mock(CitationVerificationAgent.class),
                Mockito.mock(AuditService.class), props, null,
                Mockito.mock(GstCorpusMatcherService.class), Mockito.mock(GstTemplateFillerService.class),
                Mockito.mock(RagStoreService.class), Mockito.mock(DraftingPipelineService.class), realV7Service
        );

        assertDoesNotThrow(workflow::validatePipelineConfig);
        assertThrows(UnsupportedOperationException.class, () -> realV7Service.runPipeline(null));
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
}
