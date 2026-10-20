package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IndianKanoonClient;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.agent.TemplateVariableAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.model.matching.DraftCheckResult;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.GstStateCodes;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.testsupport.BenchNoticeRows;
import com.noticedesk.api.testsupport.LiveRunGuard;
import com.noticedesk.api.testsupport.PrivateSetSupport;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

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

        // ---- cost safeguards first: no call is made when any of these refuses the run
        LiveRunGuard guard = LiveRunGuard.fromEnvironment();
        LiveRunGuard.CommitCheck commit = guard.checkCommit(LiveRunGuard.gitHead());
        System.out.println(commit.message());
        if (!commit.allowed()) {
            fail(commit.message());
        }
        if (Boolean.TRUE.equals(LiveRunGuard.gitDirty())) {
            System.out.println("WARNING: tracked files have uncommitted changes; this run is not exactly commit " + commit.head());
        }

        // pricing, effort and max_tokens come from application.yml (+ env overrides)
        AppProperties props = TestAppProperties.fromApplicationYml();

        // models only from env / application.yml (e.g. LLM_MODEL_NEW_ISSUE / LLM_MODEL_HIGH_STAKES may point the
        // writers at the cheaper matching model for a plumbing test)
        String modelMatching = guard.model("LLM_MODEL_MATCHING", props.getLlm().getModelMatching());
        String modelPartial = guard.model("LLM_MODEL_PARTIAL_DRAFTING", props.getLlm().getModelPartialDrafting());
        String modelNewIssue = guard.model("LLM_MODEL_NEW_ISSUE", props.getLlm().getModelNewIssue());
        String modelHighStakes = guard.model("LLM_MODEL_HIGH_STAKES", props.getLlm().getModelHighStakes());
        List<String> unset = new ArrayList<>();
        if (modelMatching == null) unset.add("LLM_MODEL_MATCHING");
        if (modelPartial == null) unset.add("LLM_MODEL_PARTIAL_DRAFTING");
        if (modelNewIssue == null) unset.add("LLM_MODEL_NEW_ISSUE");
        if (modelHighStakes == null) unset.add("LLM_MODEL_HIGH_STAKES");
        if (!unset.isEmpty()) {
            fail("REFUSED: set " + String.join(", ", unset) + " (no model names are built into the runner)");
        }
        String ikToken = Optional.ofNullable(System.getenv("INDIANKANOON_API_TOKEN")).orElse("");
        String citationProvider = (!ikToken.isBlank()) ? "indiankanoon" : "stub";
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().getAnthropic().setApiKey(apiKey);
        String wsId = System.getenv("ANTHROPIC_WORKSPACE_ID");
        if (wsId != null && !wsId.isBlank()) {
            props.getLlm().getAnthropic().setWorkspaceId(wsId);
        }

        props.getLlm().setModelMatching(modelMatching);
        props.getLlm().setModelPartialDrafting(modelPartial);
        props.getLlm().setModelNewIssue(modelNewIssue);
        props.getLlm().setModelHighStakes(modelHighStakes);

        props.getCitation().setProvider(citationProvider);
        props.getCitation().setIndiankanoonApiToken(ikToken);
        props.getMatching().setAllowDraftCards(true);
        // synthetic bench notices only: log a 400-char sample of any unparseable writer output
        props.getLlm().setDebugLogRawOutput(true);
        // test caps (production defaults in application.yml are unchanged): per notice LIVE_MAX_NOTICE_COST_INR
        BigDecimal noticeCap = guard.maxNoticeCostInr();
        BigDecimal runCap = guard.maxRunCostInr();
        props.getLlm().setMaxCostInrPerNotice(noticeCap.doubleValue());
        guard.printSettings(System.out);
        System.out.printf("per-notice caps: high-stakes calls=%s, cost INR=%s%n",
                props.getLlm().getHighStakes().getMaxCallsPerNotice(), props.getLlm().getMaxCostInrPerNotice());
        System.out.printf("models: matching=%s partial=%s new_issue=%s high_stakes=%s%n",
                modelMatching, modelPartial, modelNewIssue, modelHighStakes);

        // an unpriced model would make every cost cap blind: refuse instead of warning
        ApiUsageLogService pricingCheck = new ApiUsageLogService(null, props);
        for (String m : List.of(modelMatching, modelPartial, modelNewIssue, modelHighStakes)) {
            if (!pricingCheck.hasPrice(m)) {
                fail("REFUSED: no price configured for model " + m + " under noticedesk.pricing.models in application.yml");
            }
        }
        for (String agent : List.of("matching", "partial_drafting", "new_issue", "high_stakes")) {
            System.out.printf("%s: effort=%s max_tokens=%d retry_max_tokens=%d%n", agent,
                    props.getLlm().effortForAgent(agent),
                    props.getLlm().maxTokensForAgent(agent, false), props.getLlm().maxTokensForAgent(agent, true));
        }

        ObjectMapper om = new ObjectMapper();

        // one connection for the whole run, so the RLS tenant set below applies to every insert
        org.springframework.jdbc.datasource.SingleConnectionDataSource ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                "jdbc:postgresql://127.0.0.1:5432/noticedesk_dev", "noticedesk", "noticedesk", true);
        ds.setDriverClassName("org.postgresql.Driver");
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc =
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds);
        UUID tenantUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        BenchNoticeRows.setTenant(jdbc.getJdbcTemplate(), tenantUuid);
        // dev issue cards + reply/stage templates (scn_74, scn_74a, anti_profiteering included). Skipped when
        // PRIVATE_SEED_DIR is set: the dev upserts must never overwrite a private catalogue.
        String privateSeedDir = System.getenv("PRIVATE_SEED_DIR");
        if (privateSeedDir == null || privateSeedDir.isBlank()) {
            java.sql.Connection conn = ds.getConnection();   // the single shared connection: not closed here
            for (String seed : List.of("seeds/dev/seed_issue_cards_dev.sql", "seeds/dev/seed_reply_and_stage_templates_dev.sql")) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(conn,
                        new org.springframework.core.io.FileSystemResource(seed));
            }
            System.out.println("Dev seeds loaded (issue cards, reply and stage templates)");
        } else {
            System.out.println("PRIVATE_SEED_DIR set: dev seeds not loaded");
        }

        LlmFactory llmFactory = new LlmFactory(props);
        CatalogueService catalogueService = new CatalogueService(jdbc.getJdbcTemplate(), props, om);
        // a private catalogue must never be run next to the dummy cards
        String dummyCardRefusal = PrivateSetSupport.dummyCardRefusal(jdbc.getJdbcTemplate(), privateSeedDir);
        if (dummyCardRefusal != null) {
            fail(dummyCardRefusal);
        }
        System.out.println(PrivateSetSupport.catalogueLine(catalogueService));
        ApiUsageLogService usageLogService = new ApiUsageLogService(jdbc, props);
        RunCostLedger ledger = RunCostLedger.attach(usageLogService, System.out);
        guard.attach(usageLogService);

        IssueMatchingAgent matchingAgent = new IssueMatchingAgent(llmFactory, catalogueService, usageLogService, props, jdbc, om);
        PartialDraftingAgent partialAgent = new PartialDraftingAgent(props, om, usageLogService, llmFactory);
        NewIssueDraftingAgent newIssueAgent = new NewIssueDraftingAgent(props, om, usageLogService, llmFactory);
        HighStakesDraftingAgent highStakesAgent = new HighStakesDraftingAgent(props, om, usageLogService, llmFactory);
        // one call per notice on the partial-drafting model: card-specific template variables from the notice text
        TemplateVariableAgent variableAgent = new TemplateVariableAgent(props, om, usageLogService, llmFactory, catalogueService);

        IndianKanoonClient ikClient = new IndianKanoonClient(om);
        CitationVerificationAgent citationAgent = new CitationVerificationAgent(om, props, usageLogService, jdbc, ikClient);

        TemplateFillService fillService = new TemplateFillService(jdbc.getJdbcTemplate(), props, om);
        ReplyAssemblyService assemblyService = new ReplyAssemblyService(jdbc.getJdbcTemplate(), props, om);
        DraftCheckService checkService = new DraftCheckService(jdbc);

        V7DraftingPipelineService pipelineService = new V7DraftingPipelineService(
                matchingAgent, jdbc, om, fillService, assemblyService, checkService,
                partialAgent, newIssueAgent, highStakesAgent, citationAgent, variableAgent, props, usageLogService
        );
        // REQUIRED: the pipeline is built by hand here, so Spring's setter injection does not run; without this call
        // the post-matching stage correction (and the code defect card lookup) silently never runs in live tests
        pipelineService.setCatalogueService(catalogueService);

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
            Resource res = PrivateSetSupport.resolveNotice(name, privateSeedDir);
            if (res == null) {
                fail("REFUSED: notice file not found: " + name);
            }
            noticeFiles.put(name, res);
        }
        if (guard.smoke()) {
            targetNotices = Arrays.copyOf(targetNotices, 1);
            System.out.println("LIVE_SMOKE=true: only " + targetNotices[0] + " is run");
        }

        File resultsDir = new File("../testbench/results");
        if (!resultsDir.exists() && !resultsDir.mkdirs()) {
            resultsDir = new File("testbench/results");
            resultsDir.mkdirs();
        }

        BigDecimal totalRun2Inr = BigDecimal.ZERO;
        Map<String, String> statusByNotice = new LinkedHashMap<>();
        String abortReason = null;

        System.out.println("=== RUN 2: V7 LIVE PIPELINE EXECUTION REPORT ===");

        for (String noticeFile : targetNotices) {
            // a notice may spend at most the smaller of the notice cap and what is left of the run cap; the
            // pipeline's budget refuses any call (matching or writer, retries included) whose worst case won't fit
            BigDecimal thisNoticeCap = guard.noticeCapFor(totalRun2Inr);
            if (thisNoticeCap.signum() <= 0) {
                System.err.println("SAFETY CAP REACHED: run spend " + totalRun2Inr + " INR reached the run cap of INR " + runCap + ". Halting.");
                break;
            }
            props.getLlm().setMaxCostInrPerNotice(thisNoticeCap.doubleValue());

            Resource resource = noticeFiles.get(noticeFile);
            String jsonText;
            try (InputStream is = resource.getInputStream()) {
                jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root = om.readTree(jsonText);

            String noticeId = root.path("notice_id").asText();
            String docType = root.path("document_type").asText();
            String ocr = root.path("full_ocr_text").asText();
            JsonNode answerKey = root.path("answer_key");

            // header fields in either bench style; a form placeholder such as [____] is null, never a value
            BenchNoticeHeader.Header header = BenchNoticeHeader.parse(ocr);
            String ref = header.reference();
            String date = header.date();
            String clientName = header.clientName();
            String gstin = header.gstin();
            String state = GstStateCodes.nameForGstin(gstin).orElse(null);   // null -> [[MISSING: state]] in assembly
            String fy = header.financialYear();
            String section = header.section();

            Map<String, Object> noticeMap = new LinkedHashMap<>();
            noticeMap.put("notice_id", noticeId);
            noticeMap.put("tenant_id", UUID.fromString("11111111-1111-1111-1111-111111111111"));
            noticeMap.put("document_type", docType);
            if (ref != null) noticeMap.put("notice_number", ref);
            if (date != null) noticeMap.put("issue_date", date);
            if (section != null) noticeMap.put("section", section);

            UUID noticeUuid = UUID.nameUUIDFromBytes(noticeId.getBytes(StandardCharsets.UTF_8));
            // issue_matches.notice_id references notices: create the synthetic notice row first. A notice whose
            // GSTIN is a placeholder gets the synthetic bench GSTIN for its DB rows only; the draft itself sees null.
            String rowGstin = gstin != null ? gstin : BENCH_PLACEHOLDER_GSTIN;
            BenchNoticeRows.ensureNotice(jdbc.getJdbcTemplate(), tenantUuid, noticeUuid, noticeId, rowGstin, clientName, docType, fy);

            DraftingAgent.DraftingInput input = new DraftingAgent.DraftingInput(
                    noticeUuid, "formal", null, clientName, null, null, "Regular", gstin, state,
                    "CGST Act", fy, null, noticeMap, Map.of(), ocr, List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), null, null
            );

            System.out.printf("%n--- Calls for %s ---%n", noticeId);
            int callCheckpoint = ledger.size();
            DraftingPipelineResult result;
            try {
                result = pipelineService.runPipeline(input, tenantUuid, noticeUuid);
            } catch (MatchingFailedException e) {
                // no scaffold draft: the notice is marked failed and the run moves on
                BigDecimal spent = ledger.totalInrSince(callCheckpoint);
                totalRun2Inr = totalRun2Inr.add(spent);
                statusByNotice.put(noticeId, "FAILED (" + e.getMessage() + ")");
                System.out.printf("Notice %s: FAILED - %s | cost %.4f INR%n", noticeId, e.getMessage(), spent.doubleValue());
                if (e.costCap()) {
                    // the matching call did not fit the remaining budget: later notices would not fit either
                    System.out.println("RUN STOPPED: matching call refused by the cost cap (no call made); remaining notices not run.");
                    break;
                }
                if (guard.smoke()) break;
                continue;
            } catch (LlmFatalException e) {
                BigDecimal spent = ledger.totalInrSince(callCheckpoint);
                totalRun2Inr = totalRun2Inr.add(spent);
                statusByNotice.put(noticeId, "ABORTED");
                abortReason = e.getMessage();
                break;
            }

            // Model cost per notice from every billed call (failed attempts and retries included)
            double matchingInr = ledger.totalInrSince(callCheckpoint, "matching").doubleValue();
            double variablesInr = ledger.totalInrSince(callCheckpoint, TemplateVariableAgent.AGENT_KEY).doubleValue();
            double writerInr = ledger.totalInrSince(callCheckpoint).doubleValue() - matchingInr - variablesInr;
            // IndianKanoon calls are logged to the DB only (no token usage)
            Double ikInrObj = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT COALESCE(SUM(cost_inr), 0) FROM api_usage_logs WHERE notice_id = CAST(? AS UUID) AND provider = 'indiankanoon'",
                    Double.class, noticeUuid.toString());
            double ikInr = ikInrObj != null ? ikInrObj : 0.0;
            int matchingInTokens = result.totalInputTokens();
            int matchingOutTokens = result.totalOutputTokens();

            double noticeTotalInr = matchingInr + variablesInr + writerInr + ikInr;
            totalRun2Inr = totalRun2Inr.add(BigDecimal.valueOf(noticeTotalInr));

            String expectedStatus = answerKey.path("issues").path(0).path("status").asText("none");

            // Extract flags (issue-level flags come as "<FLAG>#<issue no>:<severity>", e.g. DEFECT_ADDED_BY_CODE#3:info)
            List<String> allFlags = flagList(result.checkResults());

            // Extract citation statuses
            List<String> citStatuses = new ArrayList<>();
            if (result.citations() != null) {
                for (VerifiedCitation vc : result.citations()) {
                    citStatuses.add(vc.caseName() + " -> " + vc.status());
                }
            }

            // Render full draft text
            String fullDraftText = renderDraft(noticeId, result);

            // Save full draft text to testbench/results/<noticeId>_v7_draft.txt
            File draftFile = new File(resultsDir, noticeId + "_v7_draft.txt");
            Files.writeString(draftFile.toPath(), fullDraftText, StandardCharsets.UTF_8);

            System.out.printf("%n--- Notice: %s (%s) ---%n", noticeId, noticeFile);
            System.out.printf("Expected Status (Answer Key): %s%n", expectedStatus);
            System.out.printf("Matching Step: In=%d, Out=%d | INR=%.4f%n", matchingInTokens, matchingOutTokens, matchingInr);
            System.out.printf("Variables Step: INR=%.4f%n", variablesInr);
            System.out.printf("Writer Step: INR=%.4f%n", writerInr);
            System.out.printf("IndianKanoon Step: INR=%.4f%n", ikInr);
            System.out.printf("Total Notice Cost: %.4f INR%n", noticeTotalInr);
            long blocking = result.checkResults() == null ? 0 : result.checkResults().stream()
                    .filter(c -> !c.passed() && "block".equals(c.severity())).count();
            statusByNotice.put(noticeId, "DRAFTED (" + blocking + " blocking checks failed)");
            System.out.printf("Citation Statuses: %s%n", citStatuses.isEmpty() ? "None" : citStatuses);
            System.out.printf("Shortlist: %s%n", shortlistLine(result.checkResults()));
            System.out.printf("Flags: %s%n", allFlags);
            System.out.printf("Saved draft to: %s%n", draftFile.getAbsolutePath());

            if ("notice_10_nomatch_anti_profiteering.json".equals(noticeFile)) {
                System.out.println("\n=== FULL DRAFT FOR NOTICE_10 ===");
                System.out.println(fullDraftText);
                System.out.println("=== END FULL DRAFT FOR NOTICE_10 ===\n");
            }
        }

        System.out.printf("%n=== RUN SUMMARY (commit %s%s) ===%n", commit.head(), guard.smoke() ? ", SMOKE" : "");
        statusByNotice.forEach((id, st) -> System.out.println("  " + id + ": " + st));
        System.out.printf("Total Run 2 Execution Cost: %.4f INR (run cap INR %s)%n", totalRun2Inr.doubleValue(), runCap.toPlainString());
        System.out.printf("Billed model calls: %d (failed: %d, unpriced: %d) | model cost: %.4f INR%n",
                ledger.size(), ledger.failedCalls(), ledger.unpricedCalls(), ledger.totalInr().doubleValue());
        if (abortReason != null) {
            System.err.println("LIVE RUN ABORTED: " + abortReason);
            fail("LIVE RUN ABORTED: " + abortReason);
        }
    }

    private static String renderDraft(String noticeId, DraftingPipelineResult result) {
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
     * The "Shortlist:" line of a notice from the {@code info:shortlist} flag details, e.g. "23 cards (level keyword;
     * stage 61, keyword 19, siblings 4)", plus the widening when it ran; "none (full catalogue, shortlist off)"
     * without the flag.
     */
    static String shortlistLine(List<DraftCheckResult> checkResults) {
        Map<String, Object> d = null;
        Map<String, Object> widened = null;
        if (checkResults != null) {
            for (DraftCheckResult c : checkResults) {
                Object flag = c.details() != null ? c.details().get("flag") : null;
                if (V7DraftingPipelineService.FLAG_SHORTLIST.equals(flag)) d = c.details();
                if (V7DraftingPipelineService.FLAG_SHORTLIST_WIDENED.equals(flag)) widened = c.details();
            }
        }
        if (d == null) return "none (full catalogue, shortlist off)";
        String line = d.get("size") + " cards (level " + d.get("level") + "; stage " + d.get("stage") + ", keyword "
                + d.get("keyword") + ", siblings " + d.get("siblings") + ")";
        if (widened != null) line += "; widened to " + widened.get("full_size") + " cards for issue(s) " + widened.get("unmatched_issues");
        return line;
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
