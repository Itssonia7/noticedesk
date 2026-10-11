package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiOutputParserTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void extractsTheObjectFromFencesAndProse() {
        assertEquals("v", AiOutputParser.readJsonObject(om, "```json\n{\"k\": \"v\"}\n```").get("k"));
        assertEquals("v", AiOutputParser.readJsonObject(om, "Here is the JSON you asked for:\n{\"k\": \"v\"}\nLet me know.").get("k"));
        assertEquals("v", AiOutputParser.readJsonObject(om, "﻿  {\"k\": \"v\", \"n\": {\"x\": \"}\"}}  trailing }").get("k"));
    }

    @Test
    void escapesQuotesLeftUnescapedInsideHtml() {
        Map<String, Object> m = AiOutputParser.readJsonObject(om,
                "{\"html\": \"<p>The so-called \"input\" tax and \"inward supply\".</p>\", \"next\": \"ok\"}");
        assertEquals("<p>The so-called \"input\" tax and \"inward supply\".</p>", m.get("html"));
        assertEquals("ok", m.get("next"));

        Map<String, Object> comma = AiOutputParser.readJsonObject(om, "{\"html\": \"He said \"yes\", then left.\"}");
        assertEquals("He said \"yes\", then left.", comma.get("html"));
    }

    @Test
    void reportsAShortErrorWithoutContent() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AiOutputParser.readJsonObject(om, "I could not write this reply."));
        assertEquals("no JSON object found in the response", e.getMessage());

        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> AiOutputParser.readJsonObject(om, "{\"a\": [1, 2,, 3]}"));
        assertTrue(bad.getMessage().startsWith("invalid JSON at line 1"), bad.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AiOutputParser.readJsonObject(om, "  "));
    }

    @Test
    void citationsAreDeduplicatedByNameCitationAndProvisionNumber() {
        List<RawAiCitation> raw = List.of(
                new RawAiCitation("M/s Suncraft Energy Pvt. Ltd. vs. Assistant Commissioner, (2023) 8 Centax 12 (Cal.)", "Calcutta High Court", 2023, null, "a"),
                new RawAiCitation("Suncraft Energy Private Limited v Assistant Commissioner", null, null, "quoted words", "b"),
                new RawAiCitation("SUNCRAFT ENERGY PVT LTD V. ASSISTANT COMMISSIONER", "Calcutta High Court", 2023, null, "c"),
                new RawAiCitation("Notification No. 40/2017-Central Tax (Rate)", null, null, null, "d"),
                new RawAiCitation("notification no 40/2017-central tax (rate)", null, null, null, "e"),
                new RawAiCitation("Circular No. 183/15/2022-GST", null, null, null, "f"),
                new RawAiCitation("Circular 183/15/2022-GST.", null, null, null, "g"),
                new RawAiCitation("Section 16(2)(aa)", null, null, null, "h"),
                new RawAiCitation("Sec. 16(2)(aa)", null, null, null, "i"),
                new RawAiCitation("Section 16(4)", null, null, null, "j"),
                new RawAiCitation("Union of India v. Bharti Airtel Ltd., (2022) 4 SCC 328", "Supreme Court", 2021, null, "k"));

        List<RawAiCitation> out = CitationVerificationAgent.dedupeCitations(raw);

        assertEquals(6, out.size(), out.toString());
        RawAiCitation suncraft = out.get(0);
        assertEquals("Calcutta High Court", suncraft.court());
        assertEquals("quoted words", suncraft.quotedText(), "missing fields filled from a repeat");
        assertEquals(CitationVerificationAgent.citationIdentity("Bharti Airtel Ltd v Union of India"),
                CitationVerificationAgent.citationIdentity("Bharti Airtel Limited vs Union of India [2019] 1 taxmann.com 5"));
        assertNotEquals(CitationVerificationAgent.citationIdentity("Section 16(4)"),
                CitationVerificationAgent.citationIdentity("Section 16(2)(aa)"));
    }
}
