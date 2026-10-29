package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.TemplateVariableAgent.IssueVariableRequest;
import com.noticedesk.api.agent.TemplateVariableAgent.TemplateVariablesResult;
import com.noticedesk.api.agent.TemplateVariableAgent.VariableSpec;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Template variable extraction step: parser, hints, prompt payload and the call policy (mocked provider, no live calls). */
class TemplateVariableAgentTest {

    private static final String MODEL = "claude-sonnet-5-5";

    private final ObjectMapper om = new ObjectMapper();
    private AppProperties props;
    private TemplateVariableAgent agent;

    private final List<IssueVariableRequest> requests = List.of(
            new IssueVariableRequest(1, "NDK-037", "Section 62 best-judgement assessment, return filed within 30 days",
                    List.of(new VariableSpec("issue.return_filing_date", TemplateVariableAgent.hintFor("issue.return_filing_date")),
                            new VariableSpec("issue.order_date", TemplateVariableAgent.hintFor("issue.order_date")))),
            new IssueVariableRequest(2, "NDK-050", "Section 50(3) interest",
                    List.of(new VariableSpec("issue.interest_amount", TemplateVariableAgent.hintFor("issue.interest_amount")))));

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelPartialDrafting(MODEL);
        agent = new TemplateVariableAgent(props, om, null, null);
    }

    @Test
    void goodJson_valuesPerIssue_numbersAsDigits() {
        String json = """
                {"issues": [
                   {"issue_no": 1, "variables": {"issue.return_filing_date": "16.08.2023", "issue.order_date": "02.05.2023"}},
                   {"issue_no": 2, "variables": {"issue.interest_amount": 48231}}],
                 "not_found": []}
                """;
        TemplateVariablesResult r = agent.parseOrThrow(json, MODEL, requests);

        assertEquals(Map.of("issue.return_filing_date", "16.08.2023", "issue.order_date", "02.05.2023"), r.valuesFor(1));
        assertEquals(Map.of("issue.interest_amount", "48231"), r.valuesFor(2), "a JSON number is rendered as digits");
        assertEquals(3, r.valueCount());
        assertTrue(r.notFound().isEmpty());
        assertEquals(MODEL, r.model());
        assertEquals("template_variables_v1", r.promptVersion());
        assertEquals(TemplateVariableAgent.PROMPT_VERSION, r.promptVersion());
    }

    @Test
    void unknownVariableNamesAndIssues_areIgnored_blankValuesDropped() {
        String json = """
                ```json
                {"issues": [
                   {"issue_no": 1, "variables": {"issue.return_filing_date": "16.08.2023", "issue.made_up": "x", "notice.issue_date": "01.01.2024", "issue.order_date": ""}},
                   {"issue_no": 9, "variables": {"issue.interest_amount": "1"}},
                   {"issue_no": 2, "variables": {"issue.interest_amount": null}}],
                 "not_found": ["issue.order_date", "issue.order_date", " "]}
                ```
                """;
        TemplateVariablesResult r = agent.parseOrThrow(json, MODEL, requests);

        assertEquals(Map.of("issue.return_filing_date", "16.08.2023"), r.valuesFor(1), "names not requested for the issue are ignored; blank is no value");
        assertTrue(r.valuesFor(2).isEmpty(), "null is no value");
        assertTrue(r.valuesFor(9).isEmpty(), "an issue that was not asked about is ignored");
        assertEquals(1, r.valueCount());
        assertEquals(List.of("issue.order_date"), r.notFound(), "not_found is de-duplicated and trimmed");
    }

    @Test
    void notFoundOnly_isAValidAnswer_butNeitherKeyIsNot() {
        TemplateVariablesResult none = agent.parseOrThrow("{\"not_found\": [\"issue.return_filing_date\", \"issue.order_date\", \"issue.interest_amount\"]}", MODEL, requests);
        assertEquals(0, none.valueCount());
        assertEquals(3, none.notFound().size());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> agent.parseOrThrow("{\"values\": {\"issue.order_date\": \"02.05.2023\"}}", MODEL, requests));
        assertTrue(e.getMessage().contains("'issues' and 'not_found' are both missing"), e.getMessage());
        assertFalse(e.getMessage().contains("02.05.2023"), "the validation message never quotes the content");
    }

    @Test
    void hintsFollowTheVariableName() {
        assertEquals("the return filing date (a date stated in the document, written dd.mm.yyyy)", TemplateVariableAgent.hintFor("issue.return_filing_date"));
        assertEquals("the order date (a date stated in the document, written dd.mm.yyyy)", TemplateVariableAgent.hintFor("issue.order_date"));
        assertEquals("the interest amount (an amount in rupees stated in the document, digits only)", TemplateVariableAgent.hintFor("issue.interest_amount"));
        assertEquals("the amount tax (an amount in rupees stated in the document, digits only)", TemplateVariableAgent.hintFor("issue.amount_tax"));
        assertEquals("the period (a tax period or financial year as the document writes it)", TemplateVariableAgent.hintFor("issue.period"));
        assertEquals("the reason for difference (copied exactly as the document states it)", TemplateVariableAgent.hintFor("issue.reason_for_difference"));
        assertEquals("the date of service (a date stated in the document, written dd.mm.yyyy)", TemplateVariableAgent.hintFor("notice.date_of_service"));
    }

    @Test
    void userPromptCarriesTheCappedTextAndOnlyTheRequestedVariables() throws Exception {
        props.getDrafting().setOcrExcerptChars(10);
        JsonNode payload = om.readTree(agent.buildUserPrompt("0123456789ABCDEF", requests));

        assertEquals("0123456789", payload.path("document_text").asText(), "ocr-excerpt-chars cap applies");
        assertEquals(2, payload.path("issues").size());
        JsonNode first = payload.path("issues").get(0);
        assertEquals(1, first.path("issue_no").asInt());
        assertEquals("Section 62 best-judgement assessment, return filed within 30 days", first.path("card_title").asText());
        assertEquals("issue.return_filing_date", first.path("variables").get(0).path("name").asText());
        assertEquals(TemplateVariableAgent.hintFor("issue.return_filing_date"), first.path("variables").get(0).path("hint").asText());
        assertEquals("issue.order_date", first.path("variables").get(1).path("name").asText());
        assertEquals("issue.interest_amount", payload.path("issues").get(1).path("variables").get(0).path("name").asText());
    }

    @Test
    void stubProvider_returnsNoValues_andListsEveryNameAsNotFound() {
        props.getLlm().setProviderPrimary("stub");
        TemplateVariablesResult r = agent.extractVariables("text", requests, UUID.randomUUID(), null);
        assertEquals(0, r.valueCount());
        assertEquals(List.of("issue.return_filing_date", "issue.order_date", "issue.interest_amount"), r.notFound());
        assertEquals("stub", r.model());
    }

    @Test
    void liveCall_usesThePartialDraftingModelWithLowEffortAnd2000Tokens_andLogsTheStep() {
        LlmProvider llm = Mockito.mock(LlmProvider.class);
        LlmFactory factory = Mockito.mock(LlmFactory.class);
        Mockito.when(factory.getLlmForAgent("partial_drafting")).thenReturn(llm);
        ApiUsageLogService usage = Mockito.mock(ApiUsageLogService.class);
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(MODEL), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse("{\"issues\": [{\"issue_no\": 1, \"variables\": {\"issue.order_date\": \"02.05.2023\"}}], \"not_found\": []}",
                        MODEL, "anthropic", 500, 60));
        TemplateVariableAgent live = new TemplateVariableAgent(props, om, usage, factory);

        TemplateVariablesResult r = live.extractVariables("text", requests, UUID.randomUUID(), null);

        assertEquals(Map.of("issue.order_date", "02.05.2023"), r.valuesFor(1));
        ArgumentCaptor<Integer> maxTokens = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> effort = ArgumentCaptor.forClass(String.class);
        Mockito.verify(llm).generateWithEffort(Mockito.any(), Mockito.any(), Mockito.eq(MODEL), maxTokens.capture(), effort.capture(), Mockito.eq(true));
        assertEquals(2000, maxTokens.getValue());
        assertEquals("low", effort.getValue());
        Mockito.verify(usage).logCall(Mockito.any(), Mockito.eq(MODEL), Mockito.eq("template_variables"), Mockito.eq(1), Mockito.any(), Mockito.any());
        Mockito.verify(usage).reportOutcome("template_variables", 1, true, null);
        assertEquals("template_variables", TemplateVariableAgent.AGENT_KEY);
    }

    @Test
    void missingPartialDraftingModel_isAConfigurationError() {
        props.getLlm().setModelPartialDrafting("");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> agent.extractVariables("text", requests, UUID.randomUUID(), null));
        assertTrue(e.getMessage().contains("LLM_MODEL_PARTIAL_DRAFTING"));
    }
}
