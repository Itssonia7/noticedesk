package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.model.matching.DraftCheckResult;

import java.util.*;

/**
 * Issue-level flags the partner must see: the stage correction ({@code stage_corrected}, {@code stage_mismatch}) and
 * the code defect checks ({@code defect_added_by_code}, {@code defect_confirmed_by_code}, {@code defect_disputed_by_code}).
 * They are listed on the issue's line of the Section 13 partner note and returned as draft check results named
 * {@code <FLAG>#<issue no>} ("DEFECT_ADDED_BY_CODE#3", severity info), so they appear in the draft's flag list.
 */
public final class IssueFlags {

    /** Issue flag names shown, with the severity of a flag written without one ("stage_corrected: A -> B"). */
    static final Map<String, String> VISIBLE = Map.of(
            StageResolver.ISSUE_FLAG_STAGE_CORRECTED, "info",
            StageResolver.ISSUE_FLAG_STAGE_MISMATCH, "warn",
            "defect_added_by_code", "info",
            "defect_confirmed_by_code", "info",
            "defect_disputed_by_code", "warn");

    private static final Set<String> SEVERITIES = Set.of("block", "warn", "info");

    /** One visible issue flag: its name, severity and the text after the name (may be empty). */
    public record IssueFlag(int issueNo, String name, String severity, String detail, String raw) {
        /** "DEFECT_ADDED_BY_CODE#3". */
        public String checkName() {
            return name.toUpperCase(Locale.ROOT) + "#" + issueNo;
        }

        /** "DEFECT_ADDED_BY_CODE:info". */
        public String label() {
            return name.toUpperCase(Locale.ROOT) + ":" + severity;
        }
    }

    private IssueFlags() {}

    /** The flag parsed, or null when it is not one of the visible issue flags. */
    public static IssueFlag parse(int issueNo, String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        String severity = null;
        int colon = s.indexOf(':');
        if (colon > 0 && SEVERITIES.contains(s.substring(0, colon).trim().toLowerCase(Locale.ROOT))) {
            severity = s.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            s = s.substring(colon + 1).trim();
        }
        colon = s.indexOf(':');
        String name = (colon >= 0 ? s.substring(0, colon) : s).trim().toLowerCase(Locale.ROOT);
        String detail = colon >= 0 ? s.substring(colon + 1).trim() : "";
        if (!VISIBLE.containsKey(name)) return null;
        return new IssueFlag(issueNo, name, severity != null ? severity : VISIBLE.get(name), detail, raw);
    }

    /** The visible flags of an issue, in its order. */
    public static List<IssueFlag> of(MatchedIssue issue) {
        List<IssueFlag> out = new ArrayList<>();
        if (issue == null || issue.flags() == null) return out;
        for (String f : issue.flags()) {
            IssueFlag p = parse(issue.issueNo(), f);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** One failed check result per visible issue flag ("DEFECT_ADDED_BY_CODE#3", info), details: issue_no, flag. */
    public static List<DraftCheckResult> checkResults(List<MatchedIssue> issues) {
        List<DraftCheckResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MatchedIssue issue : issues != null ? issues : List.<MatchedIssue>of()) {
            for (IssueFlag f : of(issue)) {
                if (!seen.add(f.checkName())) continue;
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("issue_no", f.issueNo());
                details.put("flag", f.raw());
                out.add(new DraftCheckResult(f.checkName(), f.severity(), false, details));
            }
        }
        return out;
    }
}
