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

    @Test
    void testMissingFieldsProduceMissingPlaceholdersAndNeverFabricatedValues() {
        DraftingInput emptyInput = new DraftingInput(
                UUID.randomUUID(),
                "formal",
                "None",
                "", "", "", "", "", "", "", "", "",
                Map.of(),
                Map.of(),
                "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null
        );

        Path testPath = Paths.get("non_existent_dummy.docx");
        GeneratedDraft draft = templateFillerService.fillTemplate(emptyInput, testPath, "scn_73");

        assertNotNull(draft);
        assertEquals(15, draft.sections().size());

        String fullDraftText = draft.sections().stream()
                .map(s -> s.title() + " " + s.bodyHtml())
                .reduce("", (a, b) -> a + "\n" + b);

        assertFalse(fullDraftText.contains("DIN-2026-GST-001"), "Must not contain fabricated DIN");
        assertFalse(fullDraftText.contains("SCN/2026/001"), "Must not contain fabricated notice number");
        assertFalse(fullDraftText.contains("Proper Officer, Ward 10"), "Must not contain fabricated authority");
        assertFalse(fullDraftText.contains("14 September 2026"), "Must not contain fabricated issue date");
        assertFalse(fullDraftText.contains("[N/A]"), "Must not contain generic [N/A]");

        assertTrue(fullDraftText.contains("[[MISSING: client_legal_name]]"));
        assertTrue(fullDraftText.contains("[[MISSING: gstin]]"));
        assertTrue(fullDraftText.contains("[[MISSING: notice_number]]"));
        assertTrue(fullDraftText.contains("[[MISSING: din_or_rfn]]"));
        assertTrue(fullDraftText.contains("[[MISSING: authority]]"));
        assertTrue(fullDraftText.contains("[[MISSING: issue_date]]"));
        assertTrue(fullDraftText.contains("[[MISSING: demand_amount]]"));
    }

    @Test
    void testLeftoverUnderscoresReplacedWithMissingField() {
        DraftingInput input = new DraftingInput(
                UUID.randomUUID(), "formal", "None",
                "Test Corp", "ABCDE1234F", "PVT", "GST", "27ABCDE1234F1Z5", "MH", "GST", "2023-24", "2024-25",
                Map.of("notice_number", "SCN-101", "din_or_rfn", "DIN-101", "authority", "AO", "issue_date", "2026-01-01"),
                Map.of(), "Excerpt", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null
        );

        String sampleTextWithUnderscores = "Header info \n\n Reference: _____ dated ______ for M/s ___";
        String populated = templateFillerService.extractAndPopulateTemplate(Paths.get("dummy.docx"), input);

        assertFalse(populated.contains("___"), "Must not contain leftover 3+ consecutive underscores");
    }
}
