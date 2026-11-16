package com.noticedesk.api.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.StageTemplate;
import com.noticedesk.api.service.layout.ReplyLayout;
import com.noticedesk.api.service.layout.ReplyLayoutBuilder;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiFragment;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AiIssueContent;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.TemplateFillService.FilledTemplateResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/**
 * The synthetic Section 73 audit notice of the v7 live run at c82df8b (fictional names, GSTINs and figures:
 * {@code src/test/resources/synthetic/notice_s73_yamuna.txt}) and a matcher result shaped like the one that run got:
 * seven issues, tax / ITC Rs 4,44,520 + 1,86,300 + 1,93,000 + 1,57,750 + 1,28,000 = Rs 11,09,570 (demand clause (a)),
 * Objection 5 split into a late fee + interest issue of Rs 59,060 (Sections 47 and 50) and a Section 125 penalty issue
 * from the same paragraph; issue 1 also cites the narrative intro paragraph 1.
 */
public final class SyntheticS73Notice {

    public static final String PATH = "src/test/resources/synthetic/notice_s73_yamuna.txt";
    /** Demand clause (a): tax / ineligible ITC for Objections 1 to 4 and 6. */
    public static final double TAX_TOTAL = 1_109_570d;
    public static final String SECTION = "Section 73(1)";
    public static final String CLIENT = "M/s Yamuna Textile Mills Private Limited";

    private SyntheticS73Notice() {}

    public static String text() {
        try {
            return Files.readString(Path.of(PATH));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<MatchedIssue> issues() {
        return List.of(
                issue(1, "demand", "Section 16(2)(aa) read with Rule 36(4)", "444520", List.of("1", "2"), null),
                issue(2, "demand", "Section 16(1)", "186300", List.of("3"), null),
                issue(3, "demand", "Section 9(3)", "193000", List.of("4"), null),
                issue(4, "demand", "Section 15(1) and 15(2)(c)", "157750", List.of("5"), null),
                issue(5, "demand", "Sections 47 and 50", "59060", List.of("6"), null),
                issue(6, "consequential", "Section 125", null, List.of("6"), 5),
                issue(7, "demand", "Section 73", "128000", List.of("7"), null));
    }

    public static MatchedIssue issue(int no, String kind, String section, String amount, List<String> paras, Integer parent) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("period", "2021-22");
        if (section != null) facts.put("legal_section", section);
        if (amount != null) facts.put("amount", amount);
        return new MatchedIssue(no, "partial", kind, List.of("CARD-" + no), List.of(), "synthetic", List.of(), facts, paras,
                List.of(), List.of(), parent);
    }

    public static MatchedNoticeInfo notice(Double total) {
        return new MatchedNoticeInfo("GEXCOM/ADJN/GST/2024-25/2267", "20241105AB0000K7D3F1", "05-11-2024", "30 days", "2021-22",
                "2021-22", total, "Assistant Commissioner", "07BBBCC5678B1Z2");
    }

    public static MatchingResult matching(List<MatchedIssue> issues, Double total) {
        Map<String, List<Integer>> paraMap = new LinkedHashMap<>();
        return new MatchingResult(notice(total), issues, paraMap, List.of(), List.of(), "hash", 1, 1, 0, 0);
    }

    public static DraftingInput input() {
        Map<String, Object> notice = new HashMap<>(Map.of("issue_date", "2024-11-05", "section", SECTION, "document_type", "scn_73"));
        return new DraftingInput(UUID.randomUUID(), "formal", null, CLIENT, null, null, "Regular",
                "07BBBCC5678B1Z2", "Delhi", "CGST Act", "2021-22", null, notice, Map.of(), text(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, null);
    }

    /** Writer output for every issue (one facts and one legal paragraph), no templates. */
    public static Map<Integer, AiIssueContent> aiFor(List<MatchedIssue> issues) {
        Map<Integer, AiIssueContent> ai = new HashMap<>();
        for (MatchedIssue i : issues) {
            ai.put(i.issueNo(), new AiIssueContent(i.issueNo(), "partial", List.of(
                    new AiFragment(4, null, "<p>The facts of issue " + i.issueNo() + " are as recorded in the books.</p>"),
                    new AiFragment(6, null, "<p>The demand on issue " + i.issueNo() + " is not sustainable in law.</p>")),
                    List.of(), "Not sustainable.")) ;
        }
        return ai;
    }

    public static AssembledReplyResult assemble(MatchingResult mr, Map<Integer, AiIssueContent> ai, List<String> notes) {
        ReplyAssemblyService svc = new ReplyAssemblyService(null, null, new ObjectMapper());
        svc.registerInMemoryStageTemplate(new StageTemplate("scn_73", 1, LocalDate.of(2017, 7, 1), LocalDate.of(2099, 12, 31), "draft",
                new TreeMap<>(Map.of("08", "<p>Not applicable.</p>",
                        "12", "<p>PRAYER: It is prayed that the proposed demand of Rs. {{issue.amount_tax}} be dropped.</p>",
                        "14", "<p>CLIENT SUMMARY: {{notice.type}}.</p>",
                        "15", "<p>FILING CHECKLIST: 1. Form GST DRC-06 2. Authorisation Letter.</p>")), null, null, null));
        List<FilledTemplateResult> fts = new ArrayList<>();
        for (MatchedIssue i : mr.issues()) {
            fts.add(new FilledTemplateResult("NONE", 0, i.primaryCardId(), null, List.of(), List.of(), List.of(),
                    List.of("no_template_available"), List.of(), List.of(), false));
        }
        return svc.assembleReply(mr, fts, input(), "scn_73", ai, null, notes);
    }

    public static ReplyLayout layout(AssembledReplyResult r) {
        return ReplyLayoutBuilder.build(r.sections().stream()
                .map(s -> new ReplyLayoutBuilder.Section(s.num(), s.title(), s.bodyHtml())).toList());
    }
}
