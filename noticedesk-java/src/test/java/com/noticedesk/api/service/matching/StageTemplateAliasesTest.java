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
import com.noticedesk.api.service.matching.StageTemplateAliases.Resolution;
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

/**
 * Detector key -> stage template alias table and the generic_scn fallback (live run 13-11-2026: an ordinary DRC-01 under
 * Section 73 after an audit was looked up as "drc-01" and blocked the draft). Assembly tests use the DEV stage templates
 * of seeds/dev/seed_reply_and_stage_templates_dev.sql; no model call.
 */
class StageTemplateAliasesTest {

    private final ObjectMapper om = new ObjectMapper();

    // ------------------------------------------------------------------ the table

    @ParameterizedTest(name = "key={0} section={1} -> {2} (fallback {3})")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            // detector key | notice section | template stage | fallback stage
            "scn_73            | Section 73   | scn_73      | generic_scn",
            "scn_74            | Section 74   | scn_74      | generic_scn",
            "scn_74a           | Section 74A  | scn_74a     | generic_scn",
            "scn               | NULL         | generic_scn | NULL",
            "generic_scn       | Section 63   | generic_scn | NULL",
            "drc-01            | Section 73   | scn_73      | generic_scn",
            "DRC-01            | Section 73   | scn_73      | generic_scn",
            "drc_01            | Section 74   | scn_74      | generic_scn",
            "drc01             | Section 74A  | scn_74a     | generic_scn",
            "DRC 01            | Section 73(1)| scn_73      | generic_scn",
            "drc-01            | NULL         | generic_scn | NULL",
            "drc-01            | Section 65   | generic_scn | NULL",
            "audit             | Section 73   | scn_73      | generic_scn",
            "audit             | Section 74   | scn_74      | generic_scn",
            "adt-01            | Section 73   | scn_73      | generic_scn",
            "audit             | Section 65   | audit       | generic_scn",
            "audit             | NULL         | audit       | generic_scn",
            // everything else is itself and never falls back to an SCN prayer
            "asmt_10           | Section 61   | asmt_10     | NULL",
            "rfd_08            | Section 54   | rfd_08      | NULL",
            "refund            | Section 54   | refund      | NULL",
            "anti_profiteering | Section 171  | anti_profiteering | NULL",
            "appeal            | Section 107  | appeal      | NULL",
            "drc_01a           | Section 73   | drc_01a     | NULL",
            "unknown           | Section 73   | unknown     | NULL",
            "NULL              | Section 73   | unknown     | NULL",
    })
    void aliasTable(String key, String section, String templateStage, String fallback) {
        Resolution r = StageTemplateAliases.resolve(key, section);
        assertEquals(templateStage, r.templateStage());
        assertEquals(fallback, r.fallbackStage());
        assertEquals(fallback != null, StageTemplateAliases.fallbackAllowed(key, section));
    }

    @Test
    void resolutionDescribesTheAlias() {
        Resolution aliased = StageTemplateAliases.resolve("DRC-01", "Section 73");
        assertTrue(aliased.aliased());
        assertEquals("drc-01", aliased.detectorKey(), "the key is reported as detected, not in its lookup form");
        assertEquals("scn_73 (alias of drc-01)", aliased.describe());
        assertEquals("rfd_08", StageTemplateAliases.resolve("RFD_08", null).templateStage(), "a non-alias key keeps its own spelling");
        Resolution same = StageTemplateAliases.resolve("scn_73", "Section 73");
        assertFalse(same.aliased());
        assertEquals("scn_73", same.describe());
        assertEquals("drc_1", StageTemplateAliases.normalise("DRC-01"));
        assertEquals("drc_1", StageTemplateAliases.normalise("drc01"));
        assertEquals("adt_1", StageTemplateAliases.normalise("ADT 01"));
        assertEquals("scn_74a", StageTemplateAliases.normalise("SCN_74A"));
        assertEquals(8, StageTemplateAliases.describeTable().size());
    }

    // ------------------------------------------------------------------ assembly with the dev stage templates

    @Test
    void drc01UnderSection73UsesTheScn73StageTemplate() throws Exception {
        AssembledReplyResult r = assemble("drc-01", "Section 73", Set.of("scn_73", "scn_74", "generic_scn"));

        String sec12 = section(r, 12);
        assertFalse(sec12.contains("[[MISSING: stage template"), sec12);
        assertTrue(sec12.contains("PRAYER"), sec12);
        assertTrue(sec12.contains("under Section 73"), sec12);
        assertTrue(section(r, 15).contains("Form GST DRC-06"), "the Section 73 filing checklist");
        assertTrue(section(r, 13).contains("Stage template: scn_73 (alias of drc-01) | Section: Section 73"), section(r, 13));
        assertFalse(r.flags().contains("block:stage_template_missing"), String.valueOf(r.flags()));
        assertFalse(r.flags().contains(StageTemplateAliases.FLAG_FALLBACK), String.valueOf(r.flags()));
        assertTrue(r.missingMarkers().stream().noneMatch(m -> m.contains("stage template")), String.valueOf(r.missingMarkers()));
        // the Section 73 procedural options are offered, not the neutral generic ones
        assertTrue(section(r, 13).contains("Section 73(10)"), section(r, 13));
    }

    @Test
    void auditOriginUnderSection74UsesTheScn74StageTemplate() throws Exception {
        AssembledReplyResult r = assemble("audit", "Section 74", Set.of("scn_73", "scn_74", "generic_scn"));
        assertTrue(section(r, 12).contains("PRAYER"));
        assertTrue(section(r, 13).contains("Stage template: scn_74 (alias of audit) | Section: Section 74"), section(r, 13));
        assertFalse(r.flags().contains("block:stage_template_missing"));
        assertTrue(section(r, 13).contains("Section 74(10)"), section(r, 13));
    }

    @Test
    void scnDocumentTypesStillResolveToTheirOwnTemplates() throws Exception {
        assertTrue(section(assemble("scn_73", "Section 73", Set.of("scn_73", "generic_scn")), 13).contains("Stage template: scn_73 |"));
        assertTrue(section(assemble("scn_74", "Section 74", Set.of("scn_74", "generic_scn")), 13).contains("Stage template: scn_74 |"));
        assertTrue(section(assemble("generic_scn", "Section 63", Set.of("generic_scn")), 13).contains("Stage template: generic_scn |"));
    }

    @Test
    void aMissingScnFamilyTemplateFallsBackToTheGenericSkeletonWithAWarn() throws Exception {
        // a private set without scn_74a: the neutral skeleton stands in; warn, not block
        AssembledReplyResult r = assemble("scn_74a", "Section 74A", Set.of("scn_73", "generic_scn"));

        String sec12 = section(r, 12);
        assertFalse(sec12.contains("[[MISSING"), sec12);
        assertTrue(sec12.contains("PRAYER"), sec12);
        assertTrue(sec12.contains("under Section 74A"), "the generic prayer names the notice's own provision: " + sec12);
        assertTrue(r.flags().contains(StageTemplateAliases.FLAG_FALLBACK), String.valueOf(r.flags()));
        assertFalse(r.flags().contains("block:stage_template_missing"), String.valueOf(r.flags()));
        assertTrue(section(r, 13).contains("Stage template: generic_scn (fallback: no scn_74a template) | Section: Section 74A"), section(r, 13));
        assertTrue(section(r, 13).contains("no 'scn_74a' template exists; the generic show cause reply skeleton"), section(r, 13));

        List<DraftCheckResult> checks = new DraftCheckService(null).flagChecks(r.flags());
        DraftCheckResult fb = checks.stream().filter(c -> c.checkName().equals("FLAG_STAGE_TEMPLATE_FALLBACK")).findFirst().orElseThrow();
        assertEquals("warn", fb.severity());
        assertTrue(checks.stream().noneMatch(c -> c.checkName().equals("FLAG_STAGE_TEMPLATE_MISSING")));

        // a DRC-01 whose provision is unknown: generic_scn directly (an alias, not a fallback)
        AssembledReplyResult plain = assemble("DRC-01", null, Set.of("scn_73", "generic_scn"));
        assertTrue(section(plain, 13).contains("Stage template: generic_scn (alias of drc-01) |"), section(plain, 13));
        assertFalse(plain.flags().contains(StageTemplateAliases.FLAG_FALLBACK));
        assertFalse(plain.flags().contains("block:stage_template_missing"));

        // a DRC-01 under Section 73 against a set that has no scn_73 either: the skeleton, named after the detected key
        AssembledReplyResult twice = assemble("DRC-01", "Section 73", Set.of("generic_scn"));
        assertTrue(section(twice, 13).contains("Stage template: generic_scn (fallback: no scn_73 template, detected drc-01) |"), section(twice, 13));
        assertTrue(twice.flags().contains(StageTemplateAliases.FLAG_FALLBACK));
    }

    @Test
    void otherStagesStillBlockWhenTheirTemplateIsMissing() throws Exception {
        // an appeal, a refund rejection or an anti-profiteering notice must never get the SCN prayer
        for (String stage : List.of("appeal", "rfd_08", "anti_profiteering", "asmt_10", "unknown")) {
            AssembledReplyResult r = assemble(stage, "Section 73", Set.of("scn_73", "generic_scn"));
            assertTrue(section(r, 12).contains("[[MISSING: stage template for " + stage + "]]"), stage + ": " + section(r, 12));
            assertTrue(r.flags().contains("block:stage_template_missing"), stage + ": " + r.flags());
            assertFalse(r.flags().contains(StageTemplateAliases.FLAG_FALLBACK), stage);
        }
        // and the SCN family without even a generic_scn template keeps the (b18) block
        AssembledReplyResult none = assemble("drc-01", "Section 74", Set.of("scn_73"));
        assertTrue(section(none, 12).contains("[[MISSING: stage template for scn_74]]"), section(none, 12));
        assertTrue(none.flags().contains("block:stage_template_missing"));
    }

    // ------------------------------------------------------------------ helpers

    private static String section(AssembledReplyResult r, int num) {
        return r.sections().stream().filter(s -> s.num() == num).findFirst().orElseThrow().bodyHtml();
    }

    private MatchingResult matching() {
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF-1", "DIN-1", "2024-10-15", "30 days", "2022-23", "2022-23", 100000.0, "Proper Officer");
        return new MatchingResult(info,
                List.of(new MatchedIssue(1, "none", List.of(), "Why", List.of(), Map.of("period", "2022-23"), List.of(), List.of())),
                Map.of(), List.of(), List.of(), "hash", 1, 1, 0, 0);
    }

    /** Assembles with the dev stage templates named in {@code registered}; {@code section} null = no provision known. */
    private AssembledReplyResult assemble(String stage, String section, Set<String> registered) throws Exception {
        Map<String, Object> notice = new HashMap<>(Map.of("issue_date", "2024-10-15"));
        if (section != null) notice.put("section", section);
        DraftingInput in = new DraftingInput(UUID.randomUUID(), "formal", null, "M/s Synthetic Traders", null, null, "Regular",
                "09SSSTT1234S1Z0", "Uttar Pradesh", "CGST Act", "2022-23", null, notice, Map.of(), "OCR", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, null);
        ReplyAssemblyService svc = new ReplyAssemblyService(null, null, om);
        for (String s : registered) {
            svc.registerInMemoryStageTemplate(new StageTemplate(s, 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                    seedStageSections(s), null, null, null));
        }
        return svc.assembleReply(matching(), List.of(), in, stage);
    }

    /** Section map of one stage template in seeds/dev/seed_reply_and_stage_templates_dev.sql. */
    private Map<String, String> seedStageSections(String stage) throws Exception {
        String sql = Files.readString(Path.of("seeds/dev/seed_reply_and_stage_templates_dev.sql"));
        Matcher m = Pattern.compile("\\(\\s*'" + Pattern.quote(stage) + "',\\s*\\d+,[^']*'[^']*',\\s*'[^']*',\\s*'[^']*',\\s*'(\\{.*?\\})'::jsonb",
                Pattern.DOTALL).matcher(sql);
        assertTrue(m.find(), "stage " + stage + " not in dev seed");
        JsonNode node = om.readTree(m.group(1).replace("''", "'"));
        Map<String, String> out = new TreeMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }
}
