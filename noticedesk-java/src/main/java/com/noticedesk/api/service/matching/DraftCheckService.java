package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledReplyResult;
import com.noticedesk.api.service.matching.ReplyAssemblyService.AssembledSection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class DraftCheckService {

    private static final Pattern CURLY_PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final Pattern UNRESOLVED_REF = Pattern.compile("\\[ref\\]", Pattern.CASE_INSENSITIVE);
    // "null and void" is a legal phrase, not a leaked null value
    private static final Pattern NULL_VALUE = Pattern.compile("\\bnull\\b(?!\\s+and\\s+void)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRAY_MARKER = Pattern.compile("\\[\\[(?!MISSING:|PENDING_AI:|PARTNER:)[^\\]]{0,40}");
    private static final Pattern INTERNAL_TEXT = Pattern.compile(
            "(?i)no template available|no_template_available|\\bCARD-\\d+\\b|\\bTPL-[A-Z0-9-]+|\\bBLK-[A-Z0-9-]+"
            + "|match status|strength note|\\[STUB\\b|\\b(?:opus|sonnet|haiku|claude|gpt-?\\d*|gemini)\\b");
    private static final Pattern SECTION_73_PROCEEDING = Pattern.compile("(?i)section\\s+73\\b");
    private static final Pattern SECTION_74_PROCEEDING = Pattern.compile("(?i)section\\s+74A?\\b");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Executes all 7 deterministic code checks on assembled reply.
     * Returns list of DraftCheckResult with severity ('block', 'warn', 'info').
     */
    public List<DraftCheckResult> runAllChecks(
            MatchingResult matchingResult,
            AssembledReplyResult assemblyResult
    ) {
        return runAllChecks(matchingResult, assemblyResult, null, null);
    }

    /**
     * @param noticeSection the provision the notice is issued under (e.g. "Section 171"); null skips the
     *                      proceeding-consistency check
     * @param stage         the stage template used (e.g. "scn_73")
     */
    public List<DraftCheckResult> runAllChecks(
            MatchingResult matchingResult,
            AssembledReplyResult assemblyResult,
            String noticeSection,
            String stage
    ) {
        List<DraftCheckResult> results = new ArrayList<>();

        List<AssembledSection> sections = assemblyResult.sections() != null ? assemblyResult.sections() : List.of();
        List<MatchedIssue> issues = matchingResult.issues() != null ? matchingResult.issues() : List.of();

        // 1. CHECK_ALL_SECTIONS_PRESENT (block)
        Set<Integer> presentSectionNums = new HashSet<>();
        for (AssembledSection s : sections) {
            presentSectionNums.add(s.num());
        }
        boolean all15Present = true;
        List<Integer> missingSectionNums = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            if (!presentSectionNums.contains(i)) {
                all15Present = false;
                missingSectionNums.add(i);
            }
        }
        results.add(new DraftCheckResult(
                "CHECK_ALL_SECTIONS_PRESENT",
                "block",
                all15Present,
                Map.of("total_sections", sections.size(), "missing_sections", missingSectionNums)
        ));

        // 2. CHECK_EVERY_ISSUE_ANSWERED (block)
        boolean allIssuesAnswered = true;
        List<Integer> unansweredIssues = new ArrayList<>();
        String sec04Html = getSectionHtml(sections, 4);
        String sec06Html = getSectionHtml(sections, 6);

        for (MatchedIssue issue : issues) {
            boolean inSec04 = sec04Html.contains("Issue #" + issue.issueNo()) || sec04Html.contains("PENDING_AI: Issue " + issue.issueNo());
            boolean inSec06 = sec06Html.contains("Issue #" + issue.issueNo()) || sec06Html.contains("PENDING_AI: Issue " + issue.issueNo());
            if (!inSec04 && !inSec06) {
                allIssuesAnswered = false;
                unansweredIssues.add(issue.issueNo());
            }
        }
        results.add(new DraftCheckResult(
                "CHECK_EVERY_ISSUE_ANSWERED",
                "block",
                allIssuesAnswered,
                Map.of("total_issues", issues.size(), "unanswered_issues", unansweredIssues)
        ));

        // 3. CHECK_PARA_MAP_ANSWERED (warn)
        Map<String, List<Integer>> paraMap = matchingResult.paraMap() != null ? matchingResult.paraMap() : Map.of();
        String sec05Html = getSectionHtml(sections, 5);
        boolean paraMapAnswered = !sec05Html.isBlank();
        results.add(new DraftCheckResult(
                "CHECK_PARA_MAP_ANSWERED",
                "block",
                paraMapAnswered,
                Map.of("para_map_categories", new ArrayList<>(new TreeSet<>(paraMap.keySet())))
        ));

        // 4. CHECK_FIGURE_CONSISTENCY (block if amounts differ from facts)
        boolean figuresConsistent = true;
        List<String> figureMismatches = new ArrayList<>();
        String fullReplyHtml = getAllSectionsHtml(sections);

        for (MatchedIssue issue : issues) {
            if (issue.facts() != null && issue.facts().containsKey("amount")) {
                Object amtObj = issue.facts().get("amount");
                if (amtObj != null) {
                    try {
                        double amt = Double.parseDouble(amtObj.toString());
                        long longAmt = Math.round(amt);
                        String formattedAmt = TemplateFillService.formatIndianCurrency(java.math.BigDecimal.valueOf(longAmt));
                        // Check if formatted amount string exists in reply text
                        String cleanNum = String.valueOf(longAmt);
                        if (!fullReplyHtml.contains(cleanNum) && !fullReplyHtml.contains(formattedAmt)) {
                            figuresConsistent = false;
                            figureMismatches.add("Issue #" + issue.issueNo() + " amount " + amt + " not found in reply text");
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        results.add(new DraftCheckResult(
                "CHECK_FIGURE_CONSISTENCY",
                "block",
                figuresConsistent,
                Map.of("mismatches", figureMismatches)
        ));

        // 5. CHECK_NO_UNRESOLVED_PLACEHOLDERS (block): over client-facing sections (all but 13), flags
        //    {{...}}, "[ref]", a bare "null", stray "[[" other than MISSING / PENDING_AI / PARTNER markers,
        //    and internal text (catalogue IDs, errors, stub output, model names).
        String clientFacingHtml = getClientFacingHtml(sections);
        String clientFacingText = clientFacingHtml.replaceAll("<!--.*?-->", "").replaceAll("<[^>]+>", " ");
        Map<String, Object> placeholderDetails = new LinkedHashMap<>();
        placeholderDetails.put("unresolved_placeholders", findAll(CURLY_PLACEHOLDER, clientFacingText, 1));
        placeholderDetails.put("unresolved_refs", findAll(UNRESOLVED_REF, clientFacingText, 0));
        placeholderDetails.put("null_values", findAll(NULL_VALUE, clientFacingText, 0));
        placeholderDetails.put("stray_markers", findAll(STRAY_MARKER, clientFacingText, 0));
        List<String> internalHits = new ArrayList<>();
        for (AssembledSection s : sections) {
            if (s.num() == 13 || s.bodyHtml() == null) continue;
            String text = s.bodyHtml().replaceAll("<!--.*?-->", "").replaceAll("<[^>]+>", " ");
            for (String hit : findAll(INTERNAL_TEXT, text, 0)) {
                internalHits.add("section " + s.num() + ": " + hit);
            }
        }
        placeholderDetails.put("internal_text", internalHits);
        boolean placeholdersClean = placeholderDetails.values().stream().allMatch(v -> ((List<?>) v).isEmpty());
        results.add(new DraftCheckResult(
                "CHECK_NO_UNRESOLVED_PLACEHOLDERS",
                "block",
                placeholdersClean,
                placeholderDetails
        ));

        // 6. CHECK_MARKERS_LIST (block if MISSING or PENDING_AI markers exist)
        Pattern missingPattern = Pattern.compile("\\[\\[MISSING: ([^\\]]+)\\]\\]");
        Pattern pendingAiPattern = Pattern.compile("\\[\\[PENDING_AI: ([^\\]]+)\\]\\]");

        Matcher missingMatcher = missingPattern.matcher(fullReplyHtml);
        List<String> missingMarkers = new ArrayList<>();
        while (missingMatcher.find()) {
            missingMarkers.add(missingMatcher.group(1));
        }

        Matcher pendingMatcher = pendingAiPattern.matcher(fullReplyHtml);
        List<String> pendingAiMarkers = new ArrayList<>();
        while (pendingMatcher.find()) {
            pendingAiMarkers.add(pendingMatcher.group(1));
        }

        boolean markersClean = missingMarkers.isEmpty() && pendingAiMarkers.isEmpty();
        results.add(new DraftCheckResult(
                "CHECK_MARKERS_LIST",
                "block",
                markersClean,
                Map.of("missing_count", missingMarkers.size(), "missing_markers", missingMarkers,
                       "pending_ai_count", pendingAiMarkers.size(), "pending_ai_markers", pendingAiMarkers)
        ));

        // 7. CHECK_NO_DUPLICATE_BLOCKS (warn)
        List<String> blockIds = new ArrayList<>();
        if (assemblyResult.paragraphSourceMap() != null) {
            for (var entry : assemblyResult.paragraphSourceMap()) {
                if (entry.blockId() != null) {
                    blockIds.add(entry.blockId());
                }
            }
        }
        Set<String> uniqueBlockIds = new HashSet<>(blockIds);
        boolean noDuplicates = blockIds.size() == uniqueBlockIds.size();
        results.add(new DraftCheckResult(
                "CHECK_NO_DUPLICATE_BLOCKS",
                "warn",
                noDuplicates,
                Map.of("total_rendered_blocks", blockIds.size(), "unique_rendered_blocks", uniqueBlockIds.size())
        ));

        // 8. CHECK_PROCEEDING_CONSISTENT (block): sections 01/02/12/14 must name the notice's own
        //    provision, and the stage template must belong to the same proceeding.
        if (noticeSection != null && !noticeSection.isBlank()) {
            List<String> problems = new ArrayList<>();
            boolean is73 = noticeSection.matches(".*\\b73\\b.*");
            boolean is74 = noticeSection.matches(".*\\b74A?\\b.*");
            for (int secNum : new int[]{1, 2, 12, 14}) {
                String text = getSectionHtml(sections, secNum);
                if (!is73 && SECTION_73_PROCEEDING.matcher(text).find()) {
                    problems.add("section " + secNum + " refers to Section 73 but the notice is under " + noticeSection);
                }
                if (!is74 && SECTION_74_PROCEEDING.matcher(text).find()) {
                    problems.add("section " + secNum + " refers to Section 74 but the notice is under " + noticeSection);
                }
            }
            if (stage != null) {
                String st = stage.toLowerCase();
                if ((st.startsWith("scn_73") && !is73) || (st.startsWith("scn_74") && !is74)) {
                    problems.add("stage template '" + stage + "' does not match the notice provision " + noticeSection
                            + "; review Sections 12 and 15");
                }
            }
            results.add(new DraftCheckResult(
                    "CHECK_PROCEEDING_CONSISTENT",
                    "block",
                    problems.isEmpty(),
                    Map.of("notice_section", noticeSection, "stage", stage != null ? stage : "", "problems", problems)
            ));
        }

        return results;
    }

    /**
     * Stage 3 citation checks over the AI citations:
     * CHECK_AI_CITATIONS_VERIFIED (block) fails on any VERIFIED_PARTIAL or NOT_CHECKED;
     * CHECK_AI_CITATIONS_REMOVED (warn) fails when NOT_FOUND citations were removed from the text.
     */
    public List<DraftCheckResult> citationChecks(List<VerifiedCitation> citations) {
        List<VerifiedCitation> list = citations != null ? citations : List.of();
        List<String> unverified = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (VerifiedCitation c : list) {
            String label = c.caseName() + " [" + c.status() + (c.details() != null ? ": " + c.details() : "") + "]";
            if ("VERIFIED_PARTIAL".equals(c.status()) || "NOT_CHECKED".equals(c.status())) {
                unverified.add(label);
            } else if ("NOT_FOUND".equals(c.status())) {
                removed.add(label);
            }
        }
        return List.of(
                new DraftCheckResult("CHECK_AI_CITATIONS_VERIFIED", "block", unverified.isEmpty(),
                        Map.of("total", list.size(), "unverified", unverified)),
                new DraftCheckResult("CHECK_AI_CITATIONS_REMOVED", "warn", removed.isEmpty(),
                        Map.of("removed", removed))
        );
    }

    /**
     * Persists check results into draft_flags table in the database after draft creation.
     */
    public void persistDraftFlags(
            UUID tenantId,
            UUID noticeId,
            UUID draftId,
            List<DraftCheckResult> checkResults
    ) {
        if (jdbcTemplate == null || tenantId == null || noticeId == null || draftId == null || checkResults == null) {
            return;
        }

        String sql = """
                INSERT INTO draft_flags (tenant_id, notice_id, draft_id, check_name, severity, passed, details)
                VALUES (:tenantId, :noticeId, :draftId, :checkName, :severity, :passed, :details::jsonb)
                """;

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        for (DraftCheckResult check : checkResults) {
            try {
                String detailsJson = mapper.writeValueAsString(check.details() != null ? check.details() : Map.of());
                MapSqlParameterSource params = new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("noticeId", noticeId)
                        .addValue("draftId", draftId)
                        .addValue("checkName", check.checkName())
                        .addValue("severity", check.severity())
                        .addValue("passed", check.passed())
                        .addValue("details", detailsJson);

                jdbcTemplate.update(sql, params);
            } catch (Exception e) {
                log.warn("Failed to persist draft_flag '{}': {}", check.checkName(), e.getMessage());
            }
        }
    }

    private String getSectionHtml(List<AssembledSection> sections, int secNum) {
        for (AssembledSection s : sections) {
            if (s.num() == secNum) {
                return s.bodyHtml() != null ? s.bodyHtml() : "";
            }
        }
        return "";
    }

    private String getClientFacingHtml(List<AssembledSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (AssembledSection s : sections) {
            if (s.num() != 13 && s.bodyHtml() != null) {
                sb.append(s.bodyHtml()).append("\n");
            }
        }
        return sb.toString();
    }

    private static List<String> findAll(Pattern p, String text, int group) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) out.add(m.group(group).trim());
        return out;
    }

    private String getAllSectionsHtml(List<AssembledSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (AssembledSection s : sections) {
            if (s.bodyHtml() != null) {
                sb.append(s.bodyHtml()).append("\n");
            }
        }
        return sb.toString();
    }
}
