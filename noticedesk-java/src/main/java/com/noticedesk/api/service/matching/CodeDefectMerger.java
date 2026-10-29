package com.noticedesk.api.service.matching;

import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.service.matching.DefectChecker.CodeDefect;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Merges the {@link DefectChecker} results into the matched issues, after matching and before the stage correction:
 * <ul>
 *   <li>a code defect the matcher also raised (a kind=defect issue whose primary card is one of the check's cards, or
 *       whose why names the check's {@code defect_checks} entry) is kept with {@link #FLAG_CONFIRMED};</li>
 *   <li>a code defect the matcher missed is ADDED as a defect issue on the card of the response stage (status full),
 *       or status none with a [[PARTNER]] note when no card covers the regime, with {@link #FLAG_ADDED};</li>
 *   <li>a check the code found ok while the matcher raised it as a defect keeps the issue with {@link #FLAG_DISPUTED}
 *       and the code's basis.</li>
 * </ul>
 * Issues are then renumbered so the defect issues follow the demand / consequential issues; parent issue numbers and
 * the paragraph map follow the new numbers.
 */
public final class CodeDefectMerger {

    /** Issue flag: a defect issue added because the code check found a defect the matcher did not raise. */
    public static final String FLAG_ADDED = "info:defect_added_by_code";
    /** Issue flag: the matcher's defect issue is confirmed by the code check. */
    public static final String FLAG_CONFIRMED = "info:defect_confirmed_by_code";
    /** Issue flag (followed by ": " and the code's basis): the code check found no defect where the matcher raised one. */
    public static final String FLAG_DISPUTED = "warn:defect_disputed_by_code";

    /** Fact key of the [[PARTNER]] text of a code-added defect issue with no catalogue card. */
    public static final String FACT_PARTNER_NOTE = "code_defect_partner_note";
    public static final String FACT_DEFECT_CHECK = "defect_check";

    /** What happened to one code defect: "added", "confirmed" or "disputed", on issue {@code issueNo} (after renumbering). */
    public record Outcome(String check, String action, int issueNo, String cardId, String basis) {}

    public record Merged(List<MatchedIssue> issues, Map<String, List<Integer>> paraMap, List<Outcome> outcomes) {
        public boolean changed(List<MatchedIssue> before) {
            return issues != before;
        }
    }

    private CodeDefectMerger() {}

    /** "[[PARTNER: &lt;basis&gt;; no catalogue card for this regime]]". */
    public static String partnerNote(String basis) {
        return "[[PARTNER: " + basis.replace("]]", "]") + "; no catalogue card for this regime]]";
    }

    /**
     * @param cardExists true for a card id in the catalogue (a card that is not becomes "no card": status none)
     * @param period     the notice's financial year / tax period for the added issues' facts (the template fill needs one)
     */
    public static Merged merge(List<MatchedIssue> issues, Map<String, List<Integer>> paraMap, List<CodeDefect> defects,
                               boolean appealStage, Predicate<String> cardExists, String period) {
        List<MatchedIssue> work = new ArrayList<>(issues != null ? issues : List.of());
        if (defects == null || defects.isEmpty()) return new Merged(issues, paraMap, List.of());
        Predicate<String> exists = cardExists != null ? cardExists : id -> true;

        // identity of each issue through the renumbering: its index in `work`
        List<Outcome> pending = new ArrayList<>();
        List<Integer> outcomeIndex = new ArrayList<>();
        Set<Integer> claimed = new HashSet<>();
        int nextNo = work.stream().mapToInt(MatchedIssue::issueNo).max().orElse(0) + 1;

        for (CodeDefect d : defects) {
            if (d == null) continue;
            if (d.isDefect()) {
                int idx = matcherIssueFor(work, d, claimed);
                if (idx >= 0) {
                    claimed.add(idx);
                    work.set(idx, withFlag(work.get(idx), FLAG_CONFIRMED));
                    pending.add(new Outcome(d.check(), "confirmed", 0, work.get(idx).primaryCardId(), d.basis()));
                    outcomeIndex.add(idx);
                    continue;
                }
                String card = d.cardFor(appealStage);
                if (card != null && !exists.test(card)) card = null;
                Map<String, Object> facts = new LinkedHashMap<>();
                if (period != null && !period.isBlank()) facts.put("period", period);
                facts.put(FACT_DEFECT_CHECK, d.check());
                facts.put("basis", d.basis());
                if (card == null) facts.put(FACT_PARTNER_NOTE, partnerNote(d.basis()));
                MatchedIssue added = new MatchedIssue(nextNo++, card != null ? "full" : "none", IssueMatchingAgent.KIND_DEFECT,
                        card != null ? List.of(card) : List.of(), List.of(), "Code check " + d.check() + ": " + d.basis(),
                        List.of(), Collections.unmodifiableMap(facts), List.of("header"), List.of(FLAG_ADDED), List.of(), null);
                work.add(added);
                claimed.add(work.size() - 1);
                pending.add(new Outcome(d.check(), "added", 0, card, d.basis()));
                outcomeIndex.add(work.size() - 1);
            } else if (d.isTestedOk()) {
                int idx = matcherIssueFor(work, d, claimed);
                if (idx < 0) continue;
                claimed.add(idx);
                work.set(idx, withFlag(work.get(idx), FLAG_DISPUTED + ": " + d.basis()));
                pending.add(new Outcome(d.check(), "disputed", 0, work.get(idx).primaryCardId(), d.basis()));
                outcomeIndex.add(idx);
            }
        }
        if (pending.isEmpty()) return new Merged(issues, paraMap, List.of());

        // renumber: demand / consequential issues first (their order kept), then the defect issues
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < work.size(); i++) if (!work.get(i).isDefect()) order.add(i);
        for (int i = 0; i < work.size(); i++) if (work.get(i).isDefect()) order.add(i);
        Map<Integer, Integer> oldToNew = new HashMap<>();
        Map<Integer, Integer> indexToNew = new HashMap<>();
        for (int pos = 0; pos < order.size(); pos++) {
            int idx = order.get(pos);
            indexToNew.put(idx, pos + 1);
            if (idx < (issues != null ? issues.size() : 0)) oldToNew.put(work.get(idx).issueNo(), pos + 1);
        }
        List<MatchedIssue> out = new ArrayList<>();
        for (int idx : order) {
            MatchedIssue i = work.get(idx);
            Integer parent = i.parentIssueNo() != null ? oldToNew.getOrDefault(i.parentIssueNo(), i.parentIssueNo()) : null;
            out.add(new MatchedIssue(indexToNew.get(idx), i.status(), i.kind(), i.cardIds(), i.alternateCardIds(), i.why(),
                    i.notThisIfChecked(), i.facts(), i.noticeParas(), i.flags(), i.clientFactsToConfirm(), parent));
        }
        Map<String, List<Integer>> newParaMap = paraMap;
        if (paraMap != null && !paraMap.isEmpty()) {
            newParaMap = new LinkedHashMap<>();
            for (Map.Entry<String, List<Integer>> e : paraMap.entrySet()) {
                List<Integer> nums = new ArrayList<>();
                for (Integer n : e.getValue() != null ? e.getValue() : List.<Integer>of()) {
                    nums.add(n != null ? oldToNew.getOrDefault(n, n) : null);
                }
                newParaMap.put(e.getKey(), nums);
            }
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (int k = 0; k < pending.size(); k++) {
            Outcome o = pending.get(k);
            outcomes.add(new Outcome(o.check(), o.action(), indexToNew.get(outcomeIndex.get(k)), o.cardId(), o.basis()));
        }
        return new Merged(List.copyOf(out), newParaMap, outcomes);
    }

    /**
     * Index of the matcher's defect issue for this check (not yet claimed by another check): its primary card is one of
     * the check's cards, or its why names the check's {@code defect_checks} entry ("defect_checks.din_rfn"); -1 if none.
     */
    static int matcherIssueFor(List<MatchedIssue> issues, CodeDefect d, Set<Integer> claimed) {
        List<String> cards = d.cards();
        String named = d.matcherCheck() != null ? "defect_checks." + d.matcherCheck() : null;
        int byWhy = -1;
        for (int i = 0; i < issues.size(); i++) {
            MatchedIssue issue = issues.get(i);
            if (claimed.contains(i) || !issue.isDefect() || hasCodeFlag(issue)) continue;
            if (issue.primaryCardId() != null && cards.contains(issue.primaryCardId())) return i;
            if (byWhy < 0 && named != null && issue.why() != null
                    && issue.why().toLowerCase(Locale.ROOT).matches("(?s).*\\b" + Pattern.quote(named) + "\\b.*")) {
                byWhy = i;
            }
        }
        return byWhy;
    }

    private static boolean hasCodeFlag(MatchedIssue issue) {
        return issue.flags() != null && issue.flags().contains(FLAG_ADDED);
    }

    private static MatchedIssue withFlag(MatchedIssue i, String flag) {
        List<String> flags = new ArrayList<>(i.flags() != null ? i.flags() : List.of());
        flags.add(flag);
        return new MatchedIssue(i.issueNo(), i.status(), i.kind(), i.cardIds(), i.alternateCardIds(), i.why(), i.notThisIfChecked(),
                i.facts(), i.noticeParas(), List.copyOf(flags), i.clientFactsToConfirm(), i.parentIssueNo());
    }
}
