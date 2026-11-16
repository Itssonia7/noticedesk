package com.noticedesk.api.testsupport;

import com.noticedesk.api.agent.DraftingAgent.DraftingInput;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedNoticeInfo;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The synthetic Section 74 notice of the 16-11-2026 live run (fictional names, GSTINs and figures:
 * {@code src/test/resources/synthetic/notice_s74_vindhya.txt}) and a matcher result shaped like the one that run got:
 * five demand issues (Rs 28,64,200 + 3,96,480 + 1,20,000 + 9,03,000 + 2,11,000 = Rs 44,94,680).
 */
public final class SyntheticS74Notice {

    public static final String PATH = "src/test/resources/synthetic/notice_s74_vindhya.txt";
    /** What the notice demands (para 8: ITC Rs 34,71,680 + IGST Rs 10,23,000). */
    public static final double TOTAL = 4_494_680d;
    /** What the live run printed as the total demand. */
    public static final double LIVE_WRONG_TOTAL = 44_946_680d;
    public static final String SECTION = "Section 74(1)";

    private SyntheticS74Notice() {}

    public static String text() {
        try {
            return Files.readString(Path.of(PATH));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<MatchedIssue> issues() {
        return List.of(
                issue(1, "Section 17(5)(d)", "28,64,200", List.of("2", "2.1"), Map.of()),
                issue(2, "Rule 42", "396480", List.of("3", "3.1"), Map.of("scrip_type", "MEIS")),
                issue(3, "Section 5(3) of the IGST Act", "120000", List.of("4", "4.1"), Map.of()),
                issue(4, "Section 77", "903000", List.of("5", "5.1"), Map.of()),
                issue(5, "Rule 86B", "211000", List.of("6", "6.1"), Map.of()));
    }

    private static MatchedIssue issue(int no, String section, String amount, List<String> paras, Map<String, Object> extra) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("period", "2019-20");
        facts.put("legal_section", section);
        facts.put("amount", amount);
        facts.putAll(extra);
        return new MatchedIssue(no, "none", "demand", List.of(), List.of(), "synthetic", List.of(), facts, paras, List.of(),
                List.of());
    }

    public static MatchedNoticeInfo notice(Double total) {
        return new MatchedNoticeInfo("C.No. IV/16/118/2024-25 Adjn.", null, "29-11-2024", "30 days", "2019-20", "2019-20", total,
                "Joint Commissioner of Central Tax", "29MMMNN9012M1Z7");
    }

    public static MatchingResult matching(Double total) {
        Map<String, List<Integer>> paraMap = new LinkedHashMap<>();
        return new MatchingResult(notice(total), issues(), paraMap, List.of(), List.of(), "hash", 1, 1, 0, 0);
    }

    public static DraftingInput input() {
        Map<String, Object> notice = new HashMap<>(Map.of("issue_date", "2024-11-29", "section", SECTION, "document_type", "scn_74"));
        return new DraftingInput(UUID.randomUUID(), "formal", null, "M/s Vindhya Build Mart Private Limited", null, null, "Regular",
                "29MMMNN9012M1Z7", "Karnataka", "CGST Act", "2019-20", null, notice, Map.of(), text(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, null);
    }
}
