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
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live run: the executive summary / notice understanding said "under Section 73" for a Section 50 interest letter and a
 * Section 75 order. The draft names the matcher's provision (legal_section of the main issue) before the notice
 * record's, and a document that is not an SCN is worded "issued under", never "Show Cause Notice".
 */
class ReplyAssemblySectionWordingTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void section50InterestLetterNamesSection50() throws Exception {
        String ocr = "Office of the Superintendent, CGST Range-II\nLetter No. INT/50/2024\nSub: Payment of interest under Section 50 "
                + "of the CGST Act on delayed filing of GSTR-3B for 2022-23 (refer Section 73 for any short payment).";
        Draft d = draft(ocr, "Section 50(1) of the CGST Act, 2017");

        assertEquals("Section 50(1)", d.section());
        assertEquals(StageDetector.GENERIC_SCN, d.stage(), "a Section 50 letter triaged scn_73 takes no Section 73 template");
        assertTrue(text(d.result(), 1).contains("issued under Section 50(1)"), text(d.result(), 1));
        assertTrue(text(d.result(), 2).contains("under Section 50(1) of the CGST Act"), text(d.result(), 2));
        for (int s : new int[]{1, 2, 14}) {
            assertFalse(text(d.result(), s).contains("Section 73"), "section " + s + ": " + text(d.result(), s));
        }
        assertTrue(proceedingCheck(d).passed(), proceedingCheck(d).details().toString());
    }

    @Test
    void section75OrderIsWordedAsAnOrder() throws Exception {
        String ocr = "ORDER-IN-ORIGINAL No. 14/2024-25\nOrder passed under Section 75 of the CGST Act, 2017 on the show cause notice dated 02.05.2024";
        Draft d = draft(ocr, "Section 75");

        assertEquals("Section 75", d.section());
        String s01 = text(d.result(), 1);
        String s02 = text(d.result(), 2);
        String s14 = text(d.result(), 14);
        assertTrue(s01.contains("Reply to Order Ref") && s01.contains("issued under Section 75"), s01);
        assertTrue(s02.contains("has received order reference") && s02.contains("issued under Section 75"), s02);
        assertTrue(s14.contains("Order issued under Section 75"), s14);
        for (String s : List.of(s01, s02, s14)) {
            assertFalse(s.contains("Show Cause Notice"), s);
            assertFalse(s.contains("Section 73"), s);
        }
        assertTrue(proceedingCheck(d).passed(), proceedingCheck(d).details().toString());
    }

    @Test
    void chargingProvisionFallsBackToTheNoticeRecord() {
        MatchingResult mr = matching("Section 16(4)");
        assertEquals("Section 73", ReplyAssemblyService.resolveNoticeSection(mr, "Section 73"));
        assertEquals("Section 74", ReplyAssemblyService.resolveNoticeSection(matching("Section 74 read with Section 16(4)"), "Section 73"));
        assertNull(ReplyAssemblyService.resolveNoticeSection(mr, null), "never a default Section 73");
        assertNull(ReplyAssemblyService.resolveNoticeSection(null, " "));
        assertTrue(ReplyAssemblyService.sameProvision("Section 73(1)", "73"));
        assertFalse(ReplyAssemblyService.sameProvision("Section 74A", "Section 74"));
    }

    @Test
    void noticeTypeLine() {
        assertEquals("Show Cause Notice under Section 73", ReplyAssemblyService.formatFullNoticeType("Section 73", StageResolver.DocumentKind.SCN));
        assertEquals("Show Cause Notice under Section 73", ReplyAssemblyService.formatFullNoticeType("Section 73", null));
        assertEquals("Letter issued under Section 50", ReplyAssemblyService.formatFullNoticeType("Section 50", StageResolver.DocumentKind.LETTER));
        assertEquals("Order issued under Section 74", ReplyAssemblyService.formatFullNoticeType("Section 74", StageResolver.DocumentKind.ORDER));
    }

    // ------------------------------------------------------------------ helpers

    private record Draft(String section, String stage, MatchingResult matching, AssembledReplyResult result) {}

    /** The pipeline's section / stage resolution, then the assembly; the notice record's header wrongly read Section 73. */
    private Draft draft(String ocr, String legalSection) throws Exception {
        MatchingResult mr = matching(legalSection);
        String recordSection = "Section 73";
        String triage = "scn_73";
        String section = ReplyAssemblyService.resolveNoticeSection(mr, recordSection);
        String stage = ReplyAssemblyService.sameProvision(section, recordSection) ? StageDetector.detect(recordSection, triage)
                : StageDetector.detect(section, triage);

        Map<String, Object> notice = new HashMap<>(Map.of("issue_date", "2024-06-10", "section", recordSection, "document_type", triage));
        DraftingInput in = new DraftingInput(UUID.randomUUID(), "formal", null, "M/s Synthetic Traders", null, null, "Regular",
                "09SSSTT1234S1Z0", "Uttar Pradesh", "CGST Act", "2022-23", null, notice, Map.of(), ocr, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, null);
        ReplyAssemblyService svc = new ReplyAssemblyService(null, null, om);
        svc.registerInMemoryStageTemplate(new StageTemplate(stage, 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                seedStageSections(StageDetector.GENERIC_SCN), null, null, null));
        return new Draft(section, stage, mr, svc.assembleReply(mr, List.of(), in, stage));
    }

    private static MatchingResult matching(String legalSection) {
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF-50", null, "2024-06-10", "30 days", "2022-23", "2022-23", 25000.0, "Superintendent");
        return new MatchingResult(info,
                List.of(new MatchedIssue(1, "none", List.of(), "why", List.of(),
                        Map.of("period", "2022-23", "legal_section", legalSection, "amount", "25000"), List.of(), List.of())),
                Map.of(), List.of(), List.of(), "hash", 1, 1, 0, 0);
    }

    private static DraftCheckResult proceedingCheck(Draft d) {
        return new DraftCheckService(null).runAllChecks(d.matching(), d.result(), d.section(), d.stage()).stream()
                .filter(c -> "CHECK_PROCEEDING_CONSISTENT".equals(c.checkName())).findFirst().orElseThrow();
    }

    private static String text(AssembledReplyResult r, int num) {
        return r.sections().stream().filter(s -> s.num() == num).map(AssembledSection::bodyHtml).findFirst().orElse("");
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
