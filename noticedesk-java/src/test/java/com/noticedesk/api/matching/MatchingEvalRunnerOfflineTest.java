package com.noticedesk.api.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmProvider;
import com.noticedesk.api.service.llm.LlmResponse;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the eval runner's per-set logic offline with a provider whose every call fails. */
class MatchingEvalRunnerOfflineTest {

    private static final String FAKE_NOTICE = """
            {"notice_id": "fake_offline_01", "document_type": "scn_73", "full_ocr_text": "FAKE NOTICE TEXT",
             "answer_key": {"issues": [{"status": "full", "card_ids": ["CARD-X"]}], "para_count": 2}}
            """;

    @Test
    void zeroSuccesses_printsNA_andStillSumsCostOfFailedCalls() throws Exception {
        ObjectMapper om = new ObjectMapper();
        AppProperties props = TestAppProperties.fromApplicationYml();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().setModelMatching("claude-sonnet-5-5");

        // every call: billed thinking tokens, no text block
        LlmProvider llm = Mockito.mock(LlmProvider.class);
        Mockito.when(llm.generateWithEffort(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.anyBoolean()))
                .thenReturn(new LlmResponse("", "claude-sonnet-5-5", "anthropic", 1_000, 5_000, "end_turn",
                        10_000, 20_000, LlmResponse.FAILURE_NO_TEXT));
        LlmFactory factory = Mockito.mock(LlmFactory.class);
        Mockito.when(factory.getLlmForAgent("matching")).thenReturn(llm);
        CatalogueService catalogue = Mockito.mock(CatalogueService.class);
        Mockito.when(catalogue.getActiveCards()).thenReturn(List.of());
        Mockito.when(catalogue.renderCatalogueCompactText(Mockito.any())).thenReturn("Catalogue");

        ApiUsageLogService usage = new ApiUsageLogService(null, props);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8);
        RunCostLedger ledger = RunCostLedger.attach(usage, capture);
        IssueMatchingAgent agent = new IssueMatchingAgent(factory, catalogue, usage, props, null, om);

        List<Resource> notices = List.of(new ByteArrayResource(FAKE_NOTICE.getBytes(StandardCharsets.UTF_8)));
        PrintStream original = System.out;
        ObjectNode summary;
        try {
            System.setOut(capture);
            summary = new MatchingEvalRunnerTest().evaluateSet("dummy", notices, false, agent, ledger, new BigDecimal[]{BigDecimal.ZERO});
        } finally {
            System.setOut(original);
        }
        String out = buf.toString(StandardCharsets.UTF_8);

        assertEquals(0, summary.path("successful_calls").asInt());
        assertTrue(out.contains("Wrong Full-Match Rate: N/A"), out);
        assertTrue(out.contains("Partial/None Correctly Flagged: N/A"), out);
        assertTrue(out.contains("Fact Accuracy: N/A"), out);
        assertTrue(out.contains("Para Coverage: N/A"), out);
        assertFalse(out.contains("100.00%"), out);
        assertTrue(summary.path("fact_accuracy_pct").isNull());

        // two failed attempts, each 7.1280 INR at Sonnet 5.5 prices (see ApiUsagePricingTest)
        assertEquals(2, ledger.size());
        assertEquals(0, new BigDecimal("14.2560").compareTo(ledger.totalInr()), ledger.totalInr().toPlainString());
        assertEquals(14.256, summary.path("total_cost_inr").asDouble(), 1e-9);
        assertTrue(out.contains("step=matching attempt=1 stop_reason=end_turn FAILED(no_text_block) in=1000 cache_read=20000 cache_write=10000 out=5000 INR=7.1280"), out);
        assertTrue(out.contains("attempt=2"), out);
    }

    @Test
    void pctHelper_naRules() {
        assertEquals("N/A", RunCostLedger.pct(0, 0, 5));
        assertEquals("N/A", RunCostLedger.pct(3, 4, 0));
        assertEquals("75.00%", RunCostLedger.pct(3, 4, 4));
        assertNull(RunCostLedger.pctValue(1, 1, 0));
    }
}
