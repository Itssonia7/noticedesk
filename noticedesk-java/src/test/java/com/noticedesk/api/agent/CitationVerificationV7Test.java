package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.IndianKanoonClient.IkDoc;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.ApiUsageLogService.UsageCall;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.util.HtmlSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** v7 citation rules with a mocked IndianKanoon client (no live calls). */
class CitationVerificationV7Test {

    private IndianKanoonClient ik;
    private CitationVerificationAgent agent;

    @BeforeEach
    void setUp() throws Exception {
        ik = mock(IndianKanoonClient.class);
        AppProperties props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken("tok");
        agent = new CitationVerificationAgent(new ObjectMapper(), props, null, null, ik);
        when(ik.search(anyString(), eq("Acme Steel Ltd v. Union of India")))
                .thenReturn(List.of(new IkDoc("7", "Acme Steel Ltd. vs Union Of India on 4 March, 2021", "Bombay High Court", "2021-03-04")));
    }

    private VerifiedCitation one(RawAiCitation c) {
        return agent.verifyAiCitations(List.of(c), UUID.randomUUID()).citations().get(0);
    }

    @Test
    void nameCourtAndYearMatchIsVerified() {
        assertEquals("VERIFIED", one(new RawAiCitation("Acme Steel Ltd v. Union of India", "High Court of Bombay", 2021, null, "x")).status());
    }

    @Test
    void wrongYearOrCourtIsPartial() {
        assertEquals("VERIFIED_PARTIAL", one(new RawAiCitation("Acme Steel Ltd v. Union of India", "Bombay High Court", 2019, null, "x")).status());
        assertEquals("VERIFIED_PARTIAL", one(new RawAiCitation("Acme Steel Ltd v. Union of India", "Supreme Court", 2021, null, "x")).status());
        assertEquals("VERIFIED_PARTIAL", one(new RawAiCitation("Acme Steel Ltd v. Union of India", null, 2021, null, "x")).status());
    }

    @Test
    void quoteNotInJudgmentIsPartial() throws Exception {
        when(ik.fragment(anyString(), eq("7"), anyString())).thenReturn("some other words");
        assertEquals("VERIFIED_PARTIAL", one(new RawAiCitation("Acme Steel Ltd v. Union of India", "Bombay High Court", 2021, "credit cannot be denied", "x")).status());
    }

    @Test
    void unknownCaseIsNotFoundAndApiErrorIsNotChecked() throws Exception {
        when(ik.search(anyString(), eq("Nobody v. Nothing"))).thenReturn(List.of());
        assertEquals("NOT_FOUND", one(new RawAiCitation("Nobody v. Nothing", "Supreme Court", 2000, null, "x")).status());
        when(ik.search(anyString(), eq("Broken v. Network"))).thenThrow(new IOException("down"));
        assertEquals("NOT_CHECKED", one(new RawAiCitation("Broken v. Network", "Supreme Court", 2000, null, "x")).status());
    }

    @Test
    void removeCitationTextKeepsArgument() {
        String html = "<p>Classification follows predominant character. Reference: Foo Pistons Ltd v. Bar (2016) 333 ELT 3 (SC).</p>";
        String out = CitationVerificationAgent.removeCitationText(html, "Foo Pistons Ltd v. Bar");
        assertFalse(out.contains("Foo Pistons"));
        assertFalse(out.contains("333 ELT"));
        assertTrue(out.contains("Classification follows predominant character."));

        String prose = "<p>As held in Foo Pistons Ltd v. Bar (2016), credit follows the invoice.</p>";
        String out2 = CitationVerificationAgent.removeCitationText(prose, "Foo Pistons Ltd v. Bar");
        assertTrue(out2.contains("credit follows the invoice."), out2);
        assertFalse(out2.contains("Foo Pistons"));
    }

    @Test
    void sanitizerKeepsOnlyAllowList() {
        String out = HtmlSanitizer.sanitize("<p class=x>a<script>bad()</script><h3>h</h3><a href='u'>l</a><table><tr><td>c</td></tr></table><!-- para:1 --></p>");
        assertEquals("<p>ahl<table><tr><td>c</td></tr></table></p>", out);
    }

    // ---- real per-call cost, per-notice cap and fatal statuses (no live calls)

    private static AppProperties pricedProps() {
        AppProperties props = new AppProperties();
        props.getCitation().setIndiankanoonApiToken("tok");
        props.getCitation().getIndiankanoon().setCostInrPerSearch(0.50);
        props.getCitation().getIndiankanoon().setCostInrPerFragment(0.25);
        return props;
    }

    @Test
    void indianKanoonCalls_areLoggedAtTheConfiguredPrice_andCountedAgainstTheNoticeCap() throws Exception {
        AppProperties props = pricedProps();
        ApiUsageLogService usage = new ApiUsageLogService(null, props);
        List<UsageCall> logged = new ArrayList<>();
        usage.addCallListener(logged::add);
        CitationVerificationAgent priced = new CitationVerificationAgent(new ObjectMapper(), props, usage, null, ik);
        when(ik.fragment(anyString(), eq("7"), anyString())).thenReturn("credit cannot be denied to the recipient");
        NoticeCallBudget budget = new NoticeCallBudget(Integer.MAX_VALUE, new BigDecimal("10"), null);
        budget.addSpentInr(new BigDecimal("2.00"));

        var result = priced.verifyAiCitations(List.of(
                new RawAiCitation("Acme Steel Ltd v. Union of India", "Bombay High Court", 2021, "credit cannot be denied", "x")),
                UUID.randomUUID(), budget);

        assertEquals("VERIFIED", result.citations().get(0).status());
        assertEquals(2, logged.size(), "one search + one fragment call logged");
        assertEquals(ApiUsageLogService.IK_PROVIDER, logged.get(0).provider());
        assertEquals(ApiUsageLogService.IK_SEARCH, logged.get(0).step());
        assertEquals(0, new BigDecimal("0.50").compareTo(logged.get(0).costInr()));
        assertEquals(ApiUsageLogService.IK_FRAGMENT, logged.get(1).step());
        assertEquals(0, new BigDecimal("0.25").compareTo(logged.get(1).costInr()));
        assertEquals(0, new BigDecimal("2.75").compareTo(budget.spentInr()), "2.00 + 0.50 + 0.25");
        assertEquals(2, result.summary().get("ik_calls"));
        // unpriced (default 0): still logged and counted, at zero
        assertEquals(0, BigDecimal.ZERO.compareTo(new ApiUsageLogService(null, new AppProperties()).indianKanoonCostInr(ApiUsageLogService.IK_SEARCH)));
    }

    @Test
    void perNoticeCostCap_stopsIndianKanoonCalls_withNotChecked() throws Exception {
        AppProperties props = pricedProps();
        CitationVerificationAgent priced = new CitationVerificationAgent(new ObjectMapper(), props, new ApiUsageLogService(null, props), null, ik);
        when(ik.search(anyString(), eq("Nobody v. Nothing"))).thenReturn(List.of());
        NoticeCallBudget budget = new NoticeCallBudget(Integer.MAX_VALUE, new BigDecimal("5"), null);
        budget.addSpentInr(new BigDecimal("4.40"));   // one more search (0.50) fits (4.90); the next one (5.40) does not

        var result = priced.verifyAiCitations(List.of(
                new RawAiCitation("Acme Steel Ltd v. Union of India", "Bombay High Court", 2021, null, "x"),
                new RawAiCitation("Nobody v. Nothing", "Supreme Court", 2000, null, "x")), UUID.randomUUID(), budget);

        assertEquals("VERIFIED", result.citations().get(0).status());
        assertEquals("NOT_CHECKED", result.citations().get(1).status());
        assertTrue(result.citations().get(1).details().startsWith("per-notice cost cap"), result.citations().get(1).details());
        assertTrue(result.flags().contains(CitationVerificationAgent.FLAG_IK_COST_CAP), result.flags().toString());
        assertEquals(1, result.summary().get("ik_calls"));
        verify(ik, never()).search(anyString(), eq("Nobody v. Nothing"));
        assertEquals(0, new BigDecimal("4.90").compareTo(budget.spentInr()));
    }

    @Test
    void authOrRateLimitFromIndianKanoon_isFatal_notNotChecked() throws Exception {
        when(ik.search(anyString(), eq("Broken v. Auth"))).thenThrow(IndianKanoonFatalException.forHttpStatus(401));
        IndianKanoonFatalException e = assertThrows(IndianKanoonFatalException.class,
                () -> agent.verifyAiCitations(List.of(new RawAiCitation("Broken v. Auth", "Supreme Court", 2000, null, "x")), UUID.randomUUID()));
        assertEquals(401, e.status());
        assertTrue(e instanceof com.noticedesk.api.service.llm.LlmFatalException, "stops the run like a provider auth error");
        assertNotNull(IndianKanoonFatalException.forHttpStatus(403));
        assertEquals(429, IndianKanoonFatalException.forHttpStatus(429).status());
        assertNull(IndianKanoonFatalException.forHttpStatus(500), "a server error stays per-citation (NOT_CHECKED)");
        assertNull(IndianKanoonFatalException.forHttpStatus(404));
    }
}
