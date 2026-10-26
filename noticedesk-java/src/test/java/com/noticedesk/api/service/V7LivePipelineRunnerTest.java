package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.matching.CardShortlister;
import com.noticedesk.api.testsupport.LiveRunGuard;
import com.noticedesk.api.testsupport.PrivateSetSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Opt-in live run of the v7 pipeline ({@code ENABLE_E2E_TESTS=true}, {@code ANTHROPIC_API_KEY}); notices from
 * {@code V7_LIVE_NOTICES} (classpath testbench/notices, {@code $PRIVATE_SEED_DIR/notices} or an absolute path).
 *
 * <p>A notice JSON is a bench notice (with {@code answer_key}) or a real notice without one. The minimal real notice:
 * <pre>{"notice_id": "real_01", "document_type": "unknown", "full_ocr_text": "..."}</pre>
 * ({@code text} is accepted for {@code full_ocr_text}; {@code document_type} may be "unknown"). A real notice prints
 * "Expected Status (Answer Key): none (real notice)" and is not scored; the shortlist, the matched issues with card ids
 * and statuses, the flags and the draft path are printed for every notice.
 *
 * <p>Writers on another provider: {@code LLM_PROVIDER_NEW_ISSUE=gemini} (and/or {@code LLM_PROVIDER_HIGH_STAKES}) with
 * {@code GEMINI_API_KEY} and {@code LLM_MODEL_NEW_ISSUE} (empty = {@code GEMINI_MODEL}); the "models:" line shows
 * provider:model for those writers.
 *
 * <p>Set-up and the per-notice report are shared with {@link V7PdfPipelineRunnerTest} ({@link V7LiveRig}). With
 * {@code PRIVATE_SEED_DIR} set, every issue the new-issue writer drafts with no catalogue card is saved to the learned
 * library ({@code $PRIVATE_SEED_DIR/learned}) and the report prints "Learned: saved LRN-xxxx for issue #N".
 */
public class V7LivePipelineRunnerTest {

    /**
     * Synthetic, allow-listed bench GSTIN (see .githooks/allowed_ids.txt) used only for the clients /
     * registrations rows of a notice whose own GSTIN is a form placeholder. It never reaches the draft.
     */
    static final String BENCH_PLACEHOLDER_GSTIN = "27ABCDE1234F1Z5";

    @Test
    void runV7LivePipelineRun2() throws Exception {
        String enableE2e = System.getenv("ENABLE_E2E_TESTS");
        if (!"true".equalsIgnoreCase(enableE2e) && !"true".equalsIgnoreCase(System.getProperty("ENABLE_E2E_TESTS"))) {
            System.out.println("Skipping V7LivePipelineRunnerTest (ENABLE_E2E_TESTS not set to true)");
            return;
        }

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping V7LivePipelineRunnerTest (ANTHROPIC_API_KEY missing)");
            return;
        }

        LiveRunGuard guard = LiveRunGuard.fromEnvironment();
        // commit check, models, prices, database, catalogue, agents and the learned library (V7LiveRig)
        V7LiveRig rig = V7LiveRig.create(apiKey, guard, List.of());
        ObjectMapper om = rig.om;

        String envNotices = System.getenv("V7_LIVE_NOTICES");
        if (envNotices == null || envNotices.isBlank()) {
            envNotices = System.getProperty("V7_LIVE_NOTICES");
        }
        String[] targetNotices;
        if (envNotices != null && !envNotices.isBlank()) {
            targetNotices = Arrays.stream(envNotices.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        } else {
            targetNotices = new String[]{
                    "notice_02_fullmatch_sec74.json",
                    "notice_07_partial_novel_machinery.json",
                    "notice_10_nomatch_anti_profiteering.json"
            };
        }
        // every entry is resolved before any call: classpath testbench/notices/<name>, else
        // $PRIVATE_SEED_DIR/notices/<name>, else an absolute path
        Map<String, Resource> noticeFiles = new LinkedHashMap<>();
        for (String name : targetNotices) {
            Resource res = PrivateSetSupport.resolveNotice(name, rig.privateSeedDir);
            if (res == null) {
                fail("REFUSED: notice file not found: " + name);
            }
            noticeFiles.put(name, res);
        }
        if (guard.smoke()) {
            targetNotices = Arrays.copyOf(targetNotices, 1);
            System.out.println("LIVE_SMOKE=true: only " + targetNotices[0] + " is run");
        }

        BigDecimal totalRun2Inr = BigDecimal.ZERO;
        Map<String, String> statusByNotice = new LinkedHashMap<>();
        String abortReason = null;

        System.out.println("=== RUN 2: V7 LIVE PIPELINE EXECUTION REPORT ===");

        for (String noticeFile : targetNotices) {
            // every notice gets the full notice cap (never shrunk to the remaining run budget, which starved the
            // writers mid-notice); the run stops BEFORE a notice when what is left of the run cap is below it. The
            // pipeline's budget refuses any call (matching or writer, retries included) whose worst case won't fit.
            String stop = guard.stopBeforeNotice(totalRun2Inr);
            if (stop != null) {
                System.out.println("RUN STOPPED: " + stop + " (run spend INR " + totalRun2Inr + "); remaining notices not run.");
                break;
            }

            Resource resource = noticeFiles.get(noticeFile);
            String jsonText;
            try (InputStream is = resource.getInputStream()) {
                jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root = om.readTree(jsonText);

            // a bench notice (answer_key) or a real notice (none; text in full_ocr_text or text); a real notice has
            // no answer key: nothing is scored
            PrivateSetSupport.NoticeJson notice = PrivateSetSupport.readNotice(root);
            V7LiveRig.NoticeOutcome outcome = rig.runNotice(notice.noticeId(), noticeFile, notice.documentType(), notice.text(),
                    notice.expectedStatus(), List.of(), BigDecimal.ZERO);
            totalRun2Inr = totalRun2Inr.add(outcome.spentInr());
            statusByNotice.put(notice.noticeId(), outcome.status());
            if (outcome.abortReason() != null) {
                abortReason = outcome.abortReason();
                break;
            }
            if (outcome.stopRun()) break;
            if (outcome.result() == null && guard.smoke()) break;
        }

        rig.printSummary("Run 2", statusByNotice, totalRun2Inr, abortReason);
    }

    static String renderDraft(String noticeId, DraftingPipelineResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== V7 DRAFT FOR ").append(noticeId).append(" ===\n\n");
        for (DraftingAgent.DraftSection sec : result.draft().sections()) {
            sb.append("================================================================================\n");
            sb.append("SECTION ").append(sec.num()).append(": ").append(sec.title()).append("\n");
            sb.append("================================================================================\n");
            sb.append(htmlToText(sec.bodyHtml())).append("\n\n");
        }
        return sb.toString();
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        return html.replaceAll("<!--[^>]*-->", "")
                .replace("<p>", "").replace("</p>", "\n")
                .replace("<h3>", "\n--- ").replace("</h3>", " ---\n")
                .replaceAll("<ul>|</ul>|<ol>|</ol>", "")
                .replace("<li>", "  * ").replace("</li>", "\n")
                .replaceAll("<table>|clusters", "")
                .replace("<tr>", "").replace("</tr>", "\n")
                .replace("<th>", "").replace("</th>", " | ")
                .replace("<td>", "").replace("</td>", " | ")
                .replace(" | \n", "\n")
                .replaceAll("<[^>]+>", "");
    }

    /**
     * The "Shortlist:" line of a notice from the {@code info:shortlist} flag details, e.g. "27 cards (pool 128; labels
     * 19, ai 14, siblings 6; titles-only 11)"; "none (full catalogue, shortlist off)" without the flag.
     */
    static String shortlistLine(List<DraftCheckResult> checkResults) {
        Map<String, Object> d = null;
        if (checkResults != null) {
            for (DraftCheckResult c : checkResults) {
                Object flag = c.details() != null ? c.details().get("flag") : null;
                if (V7DraftingPipelineService.FLAG_SHORTLIST.equals(flag)) d = c.details();
            }
        }
        if (d == null) return "none (full catalogue, shortlist off)";
        return CardShortlister.Shortlist.describe(d);
    }

    /** The "Matched issues:" line from issue_matches rows (issue_no, status, card_ids): "#1 full [NDK-001]; #2 none []". */
    static String matchedIssuesLine(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "none";
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object cards = r.get("card_ids");
            parts.add("#" + r.get("issue_no") + " " + r.get("status") + " " + (cards != null ? cards.toString().replace("\"", "") : "[]"));
        }
        return String.join("; ", parts);
    }

    /** The "Flags:" line of a notice: every failed check as "<check name>:<severity>". */
    static List<String> flagList(List<DraftCheckResult> checkResults) {
        List<String> flags = new ArrayList<>();
        if (checkResults == null) return flags;
        for (DraftCheckResult c : checkResults) {
            if (!c.passed()) flags.add(c.checkName() + ":" + c.severity());
        }
        return flags;
    }
}
