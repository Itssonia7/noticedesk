package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 3 writers against thinking-model stop reasons (mocked provider, no live calls). */
class WriterStopReasonTest {

    private static final String MODEL = "claude-opus-5-5";
    private static final String NEW_ISSUE_JSON = """
            {"section04_html": "<p>Grounds</p>", "section05_html": "", "section06_html": "<p>Submissions</p>", "citations": []}
            """;
    private static final String PARTIAL_JSON = """
            {"sections": [{"section": 4, "after_block": null, "html": "<p>Extra argument</p>"}], "citations": []}
            """;

    private final ObjectMapper om = new ObjectMapper();
    private AppProperties props;
    private LlmProvider llm;
    private LlmFactory factory;
    private ApiUsageLogService usage;

    private final MatchedIssue issue = new MatchedIssue(1, "none", List.of(), "why", List.of(), Map.of(), List.of(), List.of());
    private final MatchedNoticeInfo notice = new MatchedNoticeInfo(null, null, null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelNewIssue(MODEL);
        props.getLlm().setModelPartialDrafting("claude-sonnet-5-5");
        props.getLlm().setModelHighStakes(MODEL);
        props.getLlm().setEffortNewIssue("medium");
        props.getLlm().setEffortPartialDrafting("medium");
        llm = Mockito.mock(LlmProvider.class);
        factory = Mockito.mock(LlmFactory.class);
        Mockito.when(factory.getLlmForAgent(Mockito.anyString())).thenReturn(llm);
        usage = Mockito.mock(ApiUsageLogService.class);
    }

    private static LlmResponse failed(String stop, String failure) {
        return new LlmResponse("", MODEL, "anthropic", 1000, 16000, stop, 0, 0, failure);
    }

    private static LlmResponse ok(String json) {
        return new LlmResponse(json, MODEL, "anthropic", 1000, 4000, "end_turn", 0, 0);
    }

    private void stub(LlmResponse first, LlmResponse... rest) {
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(first, rest);
    }

    @Test
    void newIssue_maxTokens_retriedOnceWithHigherCap() {
        stub(failed("max_tokens", LlmResponse.FAILURE_MAX_TOKENS), ok(NEW_ISSUE_JSON));
        NewIssueDraftingAgent agent = new NewIssueDraftingAgent(props, om, usage, factory);

        NewIssueDraftingAgent.NewIssueDraftingResult r = agent.generateNewIssueDraft(issue, notice, "ocr", "scn", UUID.randomUUID());

        assertNotNull(r);
        Mockito.verify(llm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(MODEL), Mockito.eq(16000), Mockito.eq("medium"), Mockito.eq(true));
        Mockito.verify(llm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(MODEL), Mockito.eq(24000), Mockito.eq("medium"), Mockito.eq(true));
        Mockito.verify(usage, Mockito.times(2)).logCall(Mockito.any(), Mockito.any(), Mockito.eq("new_issue"), Mockito.anyInt(), Mockito.any(), Mockito.any());
    }

    @Test
    void newIssue_refusal_noRetry_returnsNull() {
        stub(failed("refusal", LlmResponse.FAILURE_REFUSAL));
        NewIssueDraftingAgent agent = new NewIssueDraftingAgent(props, om, usage, factory);

        assertNull(agent.generateNewIssueDraft(issue, notice, "ocr", "scn", UUID.randomUUID()));
        Mockito.verify(llm, Mockito.times(1)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean());
        Mockito.verify(usage, Mockito.times(1)).logCall(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.any());
    }

    @Test
    void partial_noTextBlock_retriedThenSucceeds() {
        stub(failed("end_turn", LlmResponse.FAILURE_NO_TEXT), ok(PARTIAL_JSON));
        PartialDraftingAgent agent = new PartialDraftingAgent(props, om, usage, factory);

        PartialDraftingAgent.PartialDraftingResult r = agent.generatePartialDrafting(issue, notice, null, "scn", UUID.randomUUID());

        assertNotNull(r);
        assertEquals(1, r.sections().size());
        Mockito.verify(llm, Mockito.times(2)).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq("claude-sonnet-5-5"),
                Mockito.eq(16000), Mockito.eq("medium"), Mockito.eq(true));
    }

    @Test
    void highStakes_maxTokens_retriedWithHigherCap_refusalNot() {
        stub(failed("max_tokens", LlmResponse.FAILURE_MAX_TOKENS), failed("refusal", LlmResponse.FAILURE_REFUSAL));
        HighStakesDraftingAgent agent = new HighStakesDraftingAgent(props, om, usage, factory);

        assertNull(agent.generateHighStakesDraft(null, null, "scn", UUID.randomUUID()));
        Mockito.verify(llm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(MODEL), Mockito.eq(16000), Mockito.any(), Mockito.eq(true));
        Mockito.verify(llm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(MODEL), Mockito.eq(24000), Mockito.any(), Mockito.eq(true));
        Mockito.verify(usage, Mockito.times(2)).logCall(Mockito.any(), Mockito.any(), Mockito.eq("high_stakes"), Mockito.anyInt(), Mockito.any(), Mockito.any());
    }
}
