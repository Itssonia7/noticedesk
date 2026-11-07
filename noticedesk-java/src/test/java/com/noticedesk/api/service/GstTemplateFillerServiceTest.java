package com.noticedesk.api.service;

import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.DraftingAgent.GeneratedDraft;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GstTemplateFillerServiceTest {

    private final GstTemplateFillerService templateFillerService = new GstTemplateFillerService();

    @Test
    void testFillTemplateReplacesPlaceholdersAndCreates15Sections() {
        DraftingInput input = new DraftingInput(
                UUID.randomUUID(),
                "formal",
                "None",
                "Acme Traders Private Limited",
                "AAACA1234A",
                "Private Limited Company",
                "GST",
                "27AAACA1234A1Z5",
                "Maharashtra",
                "GST",
                "2023-24",
                "2024-25",
                Map.of(
                        "notice_number", "SCN/27/2026/099",
                        "din_or_rfn", "DIN-2026-9988",
                        "authority", "Assistant Commissioner, Ward 10"
                ),
                Map.of(),
                "Notice under section 73",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );

        Path testPath = Paths.get("non_existent_dummy.docx");
        GeneratedDraft draft = templateFillerService.fillTemplate(input, testPath, "scn_73");

        assertNotNull(draft);
        assertEquals(15, draft.sections().size(), "Must generate 15 sections");
        assertEquals("fast_track_corpus_v1", draft.promptVersion());

        String section1Html = draft.sections().get(0).bodyHtml();
        String section3Html = draft.sections().get(2).bodyHtml();
        assertTrue(section3Html.contains("Acme Traders Private Limited"), "Must contain legal name");
        assertTrue(section1Html.contains("27AAACA1234A1Z5"), "Must contain GSTIN");
        assertTrue(section1Html.contains("AAACA1234A"), "Must contain PAN");
    }
}
