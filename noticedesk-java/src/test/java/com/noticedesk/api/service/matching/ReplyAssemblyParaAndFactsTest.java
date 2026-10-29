package com.noticedesk.api.service.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Section 05 paragraph count, tax period / date of receipt, flag checks. */
class ReplyAssemblyParaAndFactsTest {

    private static final String NOTICE_TEXT = """
            Notice under Section 171
            1. WHEREAS, a complaint was received.
            2. AND WHEREAS, investigation revealed a rate cut.
            3. IT IS OBSERVED that prices were raised.
            4. ACCORDINGLY, under Section 171.
            5. NOW THEREFORE, show cause why the amount should not be recovered.
            """;

    private ReplyAssemblyService assembly;
    private final MatchedIssue issue = new MatchedIssue(1, "none", List.of(), "w", List.of(),
            Map.of("period", "2022-23", "amount", 1500000.0), List.of("1", "3"), List.of());

    @BeforeEach
    void setUp() {
        assembly = new ReplyAssemblyService(null, null, new ObjectMapper());
    }

    private static DraftingInput input(String ocr, Map<String, Object> notice) {
        return new DraftingInput(UUID.randomUUID(), "formal", null, "Test Co", null, null, null, "TESTGSTIN", "Delhi",
                "CGST Act", "2022-23", null, notice, Map.of(), ocr, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null);
    }

    private static MatchingResult result(Map<String, List<Integer>> paraMap, String taxPeriod) {
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF/1", null, "2023-06-05", "within 21 days from receipt", "2022-23",
                taxPeriod, 1500000.0, null);
        return new MatchingResult(info, List.of(new MatchedIssue(1, "none", List.of(), "w", List.of(),
                Map.of("period", "2022-23", "amount", 1500000.0), List.of("1", "3"), List.of())),
                paraMap, List.of(), List.of(), "h", 1, 1, 0, 0);
    }

    private static Map<Integer, AiIssueContent> aiParas(String paraHtml) {
        return Map.of(1, new AiIssueContent(1, "new_issue", List.of(
                new AiFragment(4, null, "<p>Ground.</p>"), new AiFragment(5, null, paraHtml)), List.of(), null));
    }

    private static String section(AssembledReplyResult r, int num) {
        return r.sections().stream().filter(s -> s.num() == num).findFirst().orElseThrow().bodyHtml();
    }

    @Test
    void matcherParaMapInParagraphFormNoLongerCollapsesToOneParagraph() {
        // live matcher output for notice-bench-010: paragraph number -> issue numbers
        Map<String, List<Integer>> paraMap = Map.of("1", List.of(1), "3", List.of(1), "4", List.of(1), "5", List.of(1));
        AssembledReplyResult r = assembly.assembleReply(result(paraMap, "2022-23"), List.of(), input(NOTICE_TEXT, Map.of("section", "Section 171")),
                "anti_profiteering", aiParas("<li><strong>Para 1:</strong> Denied A.</li><li><strong>Para 2:</strong> Noted B.</li>"
                        + "<li><strong>Para 5:</strong> Denied C.</li>"), null, List.of());

        String s05 = section(r, 5);
        for (int p = 1; p <= 5; p++) assertTrue(s05.contains("<strong>Para " + p + ":</strong>"), "Para " + p);
        assertFalse(s05.contains("Para 6:"));
        assertTrue(s05.contains("Denied A.") && s05.contains("Noted B.") && s05.contains("Denied C."));
        assertTrue(s05.contains("<strong>Para 3:</strong> Denied. Please refer to the reply to Issue #1"));
        assertFalse(section(r, 13).contains("ignored"));
        assertFalse(r.flags().contains("warn:ai_para_beyond_notice"));
    }

    @Test
    void noticeTextParagraphsCountWhenTheMatcherMapIsShort() {
        Map<String, List<Integer>> paraMap = Map.of("issue", List.of(1));
        AssembledReplyResult r = assembly.assembleReply(result(paraMap, "2022-23"), List.of(), input(NOTICE_TEXT, Map.of("section", "Section 171")),
                "anti_profiteering", Map.of(), null, List.of());
        String s05 = section(r, 5);
        assertTrue(s05.contains("<strong>Para 5:</strong> [[PARTNER: reply to Para 5"), s05);
    }

    @Test
    void aiRepliesBeyondTheNoticeAreKeptWithAWarnFlag() {
        Map<String, List<Integer>> paraMap = Map.of("issue", List.of(1, 2));
        AssembledReplyResult r = assembly.assembleReply(result(paraMap, "2022-23"), List.of(), input(NOTICE_TEXT, Map.of("section", "Section 171")),
                "anti_profiteering", aiParas("<li><strong>Para 7:</strong> Reply to a seventh paragraph.</li>"), null, List.of());
        String s05 = section(r, 5);
        assertTrue(s05.contains("<strong>Para 7:</strong> Reply to a seventh paragraph."));
        assertTrue(s05.contains("<strong>Para 6:</strong> [[PARTNER"));
        assertTrue(r.flags().contains("warn:ai_para_beyond_notice"));
        assertTrue(section(r, 13).contains("all AI replies printed"));
    }

    @Test
    void taxPeriodAndDateOfReceiptFlowFromMatcherAndNoticeRecord() {
        AssembledReplyResult r = assembly.assembleReply(result(Map.of("issue", List.of(1)), "April 2022 to March 2023"), List.of(),
                input(NOTICE_TEXT, Map.of("section", "Section 171", "receipt_date", "2023-06-09")),
                "anti_profiteering", Map.of(), null, List.of());
        String s03 = section(r, 3);
        assertTrue(s03.contains("covers the tax period April 2022 to March 2023"), s03);
        assertTrue(s03.contains("received by the taxpayer on 09-06-2023"));
        assertTrue(section(r, 14).contains("Within 21 days from receipt (date of receipt: 09-06-2023)"));
        assertFalse(r.flags().contains("warn:missing_tax_period"));
        assertFalse(r.flags().contains("warn:missing_date_of_receipt"));
    }

    @Test
    void missingTaxPeriodAndReceiptDateAreMarkedNeverInvented() {
        MatchingResult mr = new MatchingResult(new MatchedNoticeInfo("REF/1", null, "2023-06-05", null, null, null, null, null),
                List.of(new MatchedIssue(1, "none", List.of(), "w", List.of(), Map.of(), List.of(), List.of())),
                Map.of("issue", List.of(1)), List.of(), List.of(), "h", 1, 1, 0, 0);
        AssembledReplyResult r = assembly.assembleReply(mr, List.of(), input(NOTICE_TEXT, Map.of("section", "Section 171")),
                "anti_profiteering", Map.of(), null, List.of());
        String s03 = section(r, 3);
        assertTrue(s03.contains("tax period [[MISSING: tax_period]]"));
        assertTrue(s03.contains("received by the taxpayer on [[MISSING: date_of_receipt]]"));
        assertTrue(r.flags().contains("warn:missing_tax_period"));
        assertTrue(r.flags().contains("warn:missing_date_of_receipt"));

        List<DraftCheckResult> checks = new DraftCheckService(null).flagChecks(r.flags());
        DraftCheckResult tp = checks.stream().filter(c -> c.checkName().equals("FLAG_MISSING_TAX_PERIOD")).findFirst().orElseThrow();
        assertEquals("warn", tp.severity());
        assertFalse(tp.passed());
        assertTrue(checks.stream().anyMatch(c -> c.checkName().equals("FLAG_STAGE_TEMPLATE_MISSING") && c.severity().equals("block")),
                "no anti_profiteering stage template registered here: block, no scn_73 fallback");
        assertTrue(checks.stream().noneMatch(c -> c.checkName().contains("PENDING_AI_WRITER")), "unprefixed flags stay internal");
    }

    /** Matching prompt v3.5: the matcher's para_stance decides the wording of the para-wise reply. */
    private static MatchingResult resultWithStance(Map<String, List<Integer>> paraMap, Map<String, String> stance) {
        MatchedNoticeInfo info = new MatchedNoticeInfo("REF/1", null, "2023-06-05", null, "2022-23", "2022-23", 1500000.0, null);
        List<MatchedIssue> issues = List.of(
                new MatchedIssue(1, "none", List.of(), "w", List.of(), Map.of("period", "2022-23", "amount", 1500000.0), List.of("3", "4"), List.of()),
                new MatchedIssue(2, "none", List.of(), "w", List.of(), Map.of("period", "2022-23", "amount", 100.0), List.of("4"), List.of()));
        return new MatchingResult(info, issues, paraMap, List.of(), List.of(), "h", 1, 1, 0, 0, Map.of(), stance);
    }

    @Test
    void paraStance_admittedDeniedExplainedNotApplicable_wordTheParaWiseReply() {
        Map<String, List<Integer>> paraMap = Map.of("1", List.of(1), "2", List.of(1), "3", List.of(1), "4", List.of(1, 2), "5", List.of(1));
        Map<String, String> stance = Map.of("1", "admitted", "2", "explained", "3", "denied", "4", "denied", "5", "not_applicable");
        AssembledReplyResult r = assembly.assembleReply(resultWithStance(paraMap, stance), List.of(),
                input(NOTICE_TEXT, Map.of("section", "Section 171")), "anti_profiteering", Map.of(), null, List.of());
        String s05 = section(r, 5);

        assertTrue(s05.contains("<strong>Para 1:</strong> Admitted.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 2:</strong> Explained. Please refer to the reply to Issue #1 at para 4.1.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 3:</strong> Denied. Please refer to the reply to Issue #1 at para 4.1.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 4:</strong> Denied, for the reasons stated in reply to Issues #1 and #2 at paras 4.1 and 4.2.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 5:</strong> Noted.</li>"), s05);
        assertFalse(s05.contains("Para 1:</strong> Denied"), "a factual recital is never denied");
        assertFalse(s05.contains("Para 6:"));
    }

    @Test
    void paraStance_absent_keepsTodaysWording_andAiParaRepliesStillWin() {
        Map<String, List<Integer>> paraMap = Map.of("1", List.of(1), "2", List.of(1), "3", List.of(1), "4", List.of(1, 2), "5", List.of(1));
        // no stance: the earlier output shape
        AssembledReplyResult old = assembly.assembleReply(resultWithStance(paraMap, Map.of()), List.of(),
                input(NOTICE_TEXT, Map.of("section", "Section 171")), "anti_profiteering", Map.of(), null, List.of());
        String s05 = section(old, 5);
        assertTrue(s05.contains("<strong>Para 1:</strong> Denied. Please refer to the reply to Issue #1 at para 4.1.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 5:</strong> Denied. Please refer to the reply to Issue #1 at para 4.1.</li>"), s05);
        assertFalse(s05.contains("Admitted."));
        assertFalse(s05.contains("Noted."));

        // a writer's own para reply is printed before any stance; a sub-paragraph stance covers its whole paragraph;
        // a stance for a paragraph the map does not link falls back to the issues' notice_paras
        Map<String, String> stance = Map.of("1", "admitted", "Para 2", "not_applicable", "3.1", "denied", "4", "denied", "5", "explained");
        AssembledReplyResult r = assembly.assembleReply(resultWithStance(Map.of("issue", List.of(3)), stance), List.of(),
                input(NOTICE_TEXT, Map.of("section", "Section 171")), "anti_profiteering",
                aiParas("<li><strong>Para 1:</strong> Admitted; the complaint was received as stated.</li>"), null, List.of());
        s05 = section(r, 5);
        assertTrue(s05.contains("<strong>Para 1:</strong> Admitted; the complaint was received as stated.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 2:</strong> Noted.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 3:</strong> Denied. Please refer to the reply to Issue #1 at para 4.1.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 4:</strong> Denied, for the reasons stated in reply to Issues #1 and #2 at paras 4.1 and 4.2.</li>"), s05);
        assertTrue(s05.contains("<strong>Para 5:</strong> Explained, for the reasons stated in reply to Issues #1 and #2 at paras 4.1 and 4.2.</li>"), s05);
    }

    @Test
    void stanceByPara_wholeParagraphKeyWinsOverSubParagraphKeys() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("3.1", "admitted");
        in.put("3", "denied");
        in.put("3.2", "explained");
        in.put("4(a)", "explained");
        in.put("Para 5", "not_applicable");
        in.put("header", "denied");
        in.put("0", "denied");
        in.put("6", "unknown");
        Map<Integer, String> out = ReplyAssemblyService.stanceByPara(in);
        assertEquals(Map.of(3, "denied", 4, "explained", 5, "not_applicable"), out);
        assertTrue(ReplyAssemblyService.stanceByPara(null).isEmpty());
        assertTrue(ReplyAssemblyService.stanceByPara(Map.of()).isEmpty());
    }
}
