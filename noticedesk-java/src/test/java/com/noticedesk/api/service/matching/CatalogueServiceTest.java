package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.IssueCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogueServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testStableDeterministicRenderingAndByteIdenticalOutput() {
        CatalogueService catalogueService = new CatalogueService(null, new AppProperties(), objectMapper);

        IssueCard card1 = new IssueCard("CARD-002", 1, "Sec 74 Extended Period", "Summary 2",
                List.of("Sec 04"), "2017-07-01", "2024-03-31", List.of("scn_74"), List.of("sec 74"),
                List.of("Sec 73 notice"), "TPL-02", "draft", null, null, null);

        IssueCard card2 = new IssueCard("CARD-001", 1, "Sec 73 Routine Demand", "Summary 1",
                List.of("Sec 04"), "2017-07-01", "2024-03-31", List.of("scn_73"), List.of("sec 73"),
                List.of("Sec 74 notice"), "TPL-01", "draft", null, null, null);

        // Render pass 1 (unsorted list: card2 then card1)
        String render1 = catalogueService.renderCatalogueCompactText(List.of(card1, card2));
        String hash1 = catalogueService.getCatalogueHash(render1);

        // Render pass 2 (reverse order input list: card1 then card2)
        String render2 = catalogueService.renderCatalogueCompactText(List.of(card2, card1));
        String hash2 = catalogueService.getCatalogueHash(render2);

        // Verify byte-identical rendering and identical hash for Anthropic prompt caching
        assertEquals(render1, render2, "Catalogue rendering must be byte-identical regardless of input card order");
        assertEquals(hash1, hash2, "Catalogue SHA-256 hash must be identical across calls");
        assertTrue(render1.indexOf("CARD-001") < render1.indexOf("CARD-002"), "CARD-001 must precede CARD-002 in output");
    }

    @Test
    void testEmptyCatalogueRendering() {
        CatalogueService catalogueService = new CatalogueService(null, new AppProperties(), objectMapper);
        String rendered = catalogueService.renderCatalogueCompactText(List.of());
        assertEquals("ISSUE CATALOGUE: Empty", rendered);
        assertNotNull(catalogueService.getCatalogueHash(rendered));
    }
}
