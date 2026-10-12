package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** GSTIN state names (live run, notice 011) and the generic_scn stage for non-73/74 show cause notices. */
class GstStateAndGenericStageTest {

    private final ObjectMapper om = new ObjectMapper();

    // ------------------------------------------------------------------ GST state codes

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "01 | Jammu and Kashmir",
            "07 | Delhi",
            "09 | Uttar Pradesh",
            "24 | Gujarat",
            "25 | Dadra and Nagar Haveli and Daman and Diu",
            "26 | Dadra and Nagar Haveli and Daman and Diu",
            "27 | Maharashtra",
            "29 | Karnataka",
            "33 | Tamil Nadu",
            "36 | Telangana",
            "37 | Andhra Pradesh",
            "38 | Ladakh",
            "97 | Other Territory",
            "99 | Centre Jurisdiction",
    })
    void knownStateCodes(String code, String name) {
        assertEquals(Optional.of(name), GstStateCodes.nameForCode(code));
        assertEquals(Optional.of(name), GstStateCodes.nameForGstin(code + "SSSTT1234S1Z0"));
    }

    @Test
    void unknownOrMalformedCodesGiveNoName() {
        assertTrue(GstStateCodes.nameForCode("00").isEmpty());
        assertTrue(GstStateCodes.nameForCode("39").isEmpty());
        assertTrue(GstStateCodes.nameForCode("98").isEmpty());
        assertTrue(GstStateCodes.nameForGstin(gstin("40")).isEmpty());
        assertTrue(GstStateCodes.nameForGstin("XX").isEmpty());
        assertTrue(GstStateCodes.nameForGstin("9").isEmpty());
        assertTrue(GstStateCodes.nameForGstin(null).isEmpty());
    }

    @Test
    void notice011GstinRendersUttarPradesh() {
        AssembledReplyResult r = assemble(input("09SSSTT1234S1Z0", null), "scn_73", "Section 73", List.of());
        String sec03 = r.sections().get(2).bodyHtml();
        assertTrue(sec03.contains("registered under the GST law in the State of Uttar Pradesh."), sec03);
        assertFalse(sec03.contains("Unknown State"));
        assertFalse(r.flags().contains("warn:missing_state"));
    }

    @Test
    void placeholderStateNameFromRecordIsReplacedFromGstin() {
        AssembledReplyResult r = assemble(input(gstin("33"), "Unknown State"), "scn_73", "Section 73", List.of());
        assertTrue(r.sections().get(2).bodyHtml().contains("State of Tamil Nadu."));
    }

    @Test
    void recordedStateNameIsKept() {
        AssembledReplyResult r = assemble(input(gstin("27"), "Maharashtra"), "scn_73", "Section 73", List.of());
        assertTrue(r.sections().get(2).bodyHtml().contains("State of Maharashtra."));
    }

    @Test
    void unknownStateCodeRendersMissingMarkerWithWarnFlag() {
        AssembledReplyResult r = assemble(input(gstin("40"), "Unknown State"), "scn_73", "Section 73", List.of());
        String sec03 = r.sections().get(2).bodyHtml();
        assertTrue(sec03.contains("State of [[MISSING: state]]"), sec03);
        assertFalse(sec03.contains("Unknown State"));
        assertTrue(r.flags().contains("warn:missing_state"));
        assertTrue(r.missingMarkers().contains("state"));
    }

    // ------------------------------------------------------------------ generic_scn stage

    @Test
    void notice011ProvisionsDetectGenericScnNotScn73() {
        assertEquals(StageDetector.GENERIC_SCN, StageDetector.detect("Section 27 / Section 63 / Section 129", "scn_73"));
        assertEquals(StageDetector.GENERIC_SCN, StageDetector.detect("Section 63", "scn_73"));
        assertEquals("scn_73", StageDetector.detect("Section 73", "scn_73"));
    }

    @Test
    void devSeedHasNeutralGenericScnStageTemplate() throws Exception {
        Map<String, String> sections = seedStageSections("generic_scn");
        assertEquals(Set.of("08", "12", "14", "15"), sections.keySet());
        for (Map.Entry<String, String> e : sections.entrySet()) {
            String html = e.getValue();
            assertTrue(html.contains("[DEV - not CA reviewed] DRAFT - partner to review"), "section " + e.getKey());
            assertFalse(html.matches("(?s).*\\b7[34]A?\\b.*"), "no Section 73/74 wording in section " + e.getKey());
            assertFalse(html.contains("75(4)"), "no Section 75(4) wording in section " + e.getKey());
            assertFalse(html.contains("DRC-06"), "no DRC-06 (Section 73/74 reply form) in section " + e.getKey());
        }
        assertTrue(sections.get("12").contains("personal hearing be granted before any adverse order is passed"));
    }

    @Test
    void genericScnProceduralOptionsAreNeutral() {
        List<String> opts = ReplyAssemblyService.proceduralOptionsForStage(StageDetector.GENERIC_SCN, "Section 63");
        assertFalse(opts.isEmpty());
        for (String o : opts) {
            assertFalse(o.contains("73(10)") || o.contains("74(10)") || o.contains("75(4)"), o);
        }
        assertTrue(opts.get(0).contains("personal hearing has been offered before any adverse order"));
    }

    @Test
    void genericScnDraftDropsSection73OptionsAndPassesProceedingCheck() throws Exception {
        FilledTemplateResult ft = new FilledTemplateResult("TPL-X", 1, "CARD-X", "Summary.", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of("Verify the limitation period under Section 73(10).", "Check that the DIN is quoted."), false);
        AssembledReplyResult r = assemble(input("09SSSTT1234S1Z0", null), StageDetector.GENERIC_SCN, "Section 63", List.of(ft));
        String sec13 = r.sections().get(12).bodyHtml();
        assertFalse(sec13.contains("73(10)"), sec13);
        assertFalse(sec13.contains("75(4)"), sec13);
        assertTrue(sec13.contains("Check that the DIN is quoted."));
        assertTrue(r.sections().get(11).bodyHtml().contains("under Section 63"));

        MatchingResult mr = matching();
        List<DraftCheckResult> checks = new DraftCheckService(null).runAllChecks(mr, r, "Section 63", StageDetector.GENERIC_SCN);
        DraftCheckResult proceeding = checks.stream().filter(c -> c.checkName().equals("CHECK_PROCEEDING_CONSISTENT")).findFirst().orElseThrow();
        assertTrue(proceeding.passed(), String.valueOf(proceeding.details()));

        // the old fallback is still caught
        AssembledReplyResult wrong = assemble(input("09SSSTT1234S1Z0", null), "scn_73", "Section 63", List.of());
        DraftCheckResult wrongCheck = new DraftCheckService(null).runAllChecks(mr, wrong, "Section 63", "scn_73").stream()
                .filter(c -> c.checkName().equals("CHECK_PROCEEDING_CONSISTENT")).findFirst().orElseThrow();
        assertFalse(wrongCheck.passed());
    }

    // ------------------------------------------------------------------ helpers

    /** Synthetic GSTIN with the given state code (built at runtime: only 09SSSTT1234S1Z0 is allow-listed). */
    private static String gstin(String stateCode) {
        return stateCode + "SSSTT1234S1Z0";
    }

    private DraftingInput input(String gstin, String stateName) {
        return new DraftingInput(UUID.randomUUID(), "formal", null, "M/s Synthetic Traders", null, null, "Regular", gstin, stateName,
                "CGST Act", "2022-23", null, Map.of("issue_date", "2023-10-15"), Map.of(), "OCR", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), null, null);
    }

    private MatchingResult matching() {
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF-1", "DIN-1", "2023-10-15", "30 days", "2022-23", "2022-23", 100000.0, "Proper Officer");
        return new MatchingResult(info,
                List.of(new MatchedIssue(1, "full", List.of("CARD-X"), "Why", List.of(), Map.of("period", "2022-23"), List.of(), List.of())),
                Map.of(), List.of(), List.of(), "hash", 1, 1, 0, 0);
    }

    private AssembledReplyResult assemble(DraftingInput baseInput, String stage, String section, List<FilledTemplateResult> fts) {
        Map<String, Object> notice = new HashMap<>(baseInput.notice());
        notice.put("section", section);
        DraftingInput in = new DraftingInput(baseInput.matterId(), baseInput.tone(), null, baseInput.clientLegalName(), null, null,
                "Regular", baseInput.registrationIdentifier(), baseInput.registrationStateName(), "CGST Act", "2022-23", null,
                notice, Map.of(), "OCR", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
        ReplyAssemblyService svc = new ReplyAssemblyService(null, null, om);
        try {
            svc.registerInMemoryStageTemplate(new StageTemplate(stage, 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                    "generic_scn".equals(stage) ? seedStageSections("generic_scn") : Map.of(
                            "08", "<p>Cross exam</p>",
                            "12", "<p>PRAYER: drop the proceedings under {{notice.section}}; hearing as mandated under Section 75(4).</p>",
                            "14", "<p>Client summary</p>",
                            "15", "<p>FILING CHECKLIST: Form GST DRC-06</p>"),
                    null, null, null));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return svc.assembleReply(matching(), fts, in, stage);
    }

    /** Section map of one stage template in seeds/dev/seed_reply_and_stage_templates_dev.sql. */
    private Map<String, String> seedStageSections(String stage) throws Exception {
        String sql = Files.readString(Path.of("seeds/dev/seed_reply_and_stage_templates_dev.sql"));
        Matcher m = Pattern.compile("\\(\\s*'" + Pattern.quote(stage) + "',\\s*\\d+,[^']*'[^']*',\\s*'[^']*',\\s*'[^']*',\\s*'(\\{.*?\\})'::jsonb", Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "stage " + stage + " not in dev seed");
        JsonNode node = om.readTree(m.group(1).replace("''", "'"));
        Map<String, String> out = new TreeMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }
}
