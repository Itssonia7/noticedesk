package com.noticedesk.api.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent.AiIssueSectionRecord;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.AuditService;
import com.noticedesk.api.service.DraftingPipelineResult;
import com.noticedesk.api.service.DraftingPipelineService;
import com.noticedesk.api.service.GstCorpusMatcherService;
import com.noticedesk.api.service.GstTemplateFillerService;
import com.noticedesk.api.service.V7DraftingPipelineService;
import com.noticedesk.api.service.rag.RagStoreService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DraftingWorkflowAiSectionsTest {

    @Test
    void aiIssueSectionsAreSavedAfterTheDraftRowInsert() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID(), matterId = UUID.randomUUID(), noticeId = UUID.randomUUID();

        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(contains("set_config"), anyMap(), eq(String.class))).thenReturn("ok");
        when(jdbc.queryForObject(contains("MAX(version)"), anyMap(), eq(Integer.class))).thenReturn(1);
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);

        DraftingAgent.DraftingInput input = mock(DraftingAgent.DraftingInput.class);
        DraftingAgent agent = mock(DraftingAgent.class);
        when(agent.loadDraftingInput(any(), eq(matterId), eq(noticeId), any(), any(), anyBoolean())).thenReturn(input);

        List<AiIssueSectionRecord> rows = List.of(new AiIssueSectionRecord(1, "test-model", "new_issue_v2", Map.of("writer", "new_issue"), "pending_review"));
        DraftingPipelineResult result = new DraftingPipelineResult(
                new DraftingAgent.GeneratedDraft(List.of(new DraftingAgent.DraftSection(1, "T", "B")), "n", "c", "v7", "test-model", "formal", 0, 1),
                List.of(), Map.of("total", 0), 0, 0, 1, 0, 0, "end_turn", List.of(), List.of(), rows);
        V7DraftingPipelineService v7 = mock(V7DraftingPipelineService.class);
        when(v7.runPipeline(input, tenantId, noticeId)).thenReturn(result);

        AppProperties props = new AppProperties();
        props.getDrafting().setPipeline("v7");
        DraftingWorkflow wf = new DraftingWorkflow(jdbc, agent, mock(CitationVerificationAgent.class), mock(AuditService.class),
                props, new ObjectMapper(), mock(GstCorpusMatcherService.class), mock(GstTemplateFillerService.class),
                mock(RagStoreService.class), mock(DraftingPipelineService.class), v7);

        wf.runGenerateDraft(new DraftingWorkflow.GenerateDraftJob(tenantId, userId, matterId, noticeId, "formal", null, false));

        InOrder order = inOrder(jdbc, v7);
        order.verify(jdbc).update(contains("INSERT INTO drafts"), anyMap());
        order.verify(v7).persistAiIssueSections(eq(tenantId), eq(noticeId), any(UUID.class), eq(rows));
    }
}
