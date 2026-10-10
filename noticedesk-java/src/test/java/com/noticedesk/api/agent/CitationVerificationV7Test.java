package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.IndianKanoonClient.IkDoc;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.util.HtmlSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
}
