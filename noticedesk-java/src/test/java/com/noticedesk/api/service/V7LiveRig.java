package com.noticedesk.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.DraftingAgent;
import com.noticedesk.api.agent.HighStakesDraftingAgent;
import com.noticedesk.api.agent.IndianKanoonClient;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.NewIssueDraftingAgent;
import com.noticedesk.api.agent.PartialDraftingAgent;
import com.noticedesk.api.agent.ShortlistTitleAgent;
import com.noticedesk.api.agent.TemplateVariableAgent;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.learned.LearnedLibrary;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.matching.DraftCheckService;
import com.noticedesk.api.service.matching.GstStateCodes;
import com.noticedesk.api.service.matching.ReplyAssemblyService;
import com.noticedesk.api.service.matching.TemplateFillService;
import com.noticedesk.api.testsupport.BenchNoticeRows;
import com.noticedesk.api.testsupport.LiveRunGuard;
import com.noticedesk.api.testsupport.PrivateSetSupport;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The live v7 pipeline set-up shared by {@link V7LivePipelineRunnerTest} (notice JSON) and
 * {@link V7PdfPipelineRunnerTest} (PDF -> OCR -> triage): models and prices checked before any call, the local
 * database, the catalogue (dev seeds unless {@code PRIVATE_SEED_DIR} is set), every agent of the pipeline, the cost
 * ledger, the fail-fast guard and the learned library. {@link #runNotice} runs one notice and prints its report lines.
 */
final class V7LiveRig {

    /**
     * Synthetic, allow-listed bench GSTIN (see .githooks/allowed_ids.txt) used only for the clients /
     * registrations rows of a notice whose own GSTIN is a form placeholder. It never reaches the draft.
     */
    static final String BENCH_PLACEHOLDER_GSTIN = V7LivePipelineRunnerTest.BENCH_PLACEHOLDER_GSTIN;
    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    final LiveRunGuard guard;
    final LiveRunGuard.CommitCheck commit;
    final AppProperties props;
    final ObjectMapper om = new ObjectMapper();
    final NamedParameterJdbcTemplate jdbc;
    final LlmFactory llmFactory;
    final CatalogueService catalogueService;
    final ApiUsageLogService usageLogService;
    final RunCostLedger ledger;
    final V7DraftingPipelineService pipelineService;
    final LearnedLibrary learnedLibrary;
    final File resultsDir;
    final String privateSeedDir;
    final BigDecimal noticeCap;
    final BigDecimal runCap;

    /** Outcome of one notice: {@code stopRun} ends the run (cost cap), {@code abortReason} aborts it (fatal call error). */
    record NoticeOutcome(String status, BigDecimal spentInr, boolean stopRun, String abortReason,
                         DraftingPipelineResult result, File draftFile) {}

    private V7LiveRig(LiveRunGuard guard, LiveRunGuard.CommitCheck commit, AppProperties props, NamedParameterJdbcTemplate jdbc,
                      LlmFactory llmFactory, CatalogueService catalogueService, ApiUsageLogService usageLogService,
                      RunCostLedger ledger, V7DraftingPipelineService pipelineService, LearnedLibrary learnedLibrary,
                      File resultsDir, String privateSeedDir, BigDecimal noticeCap, BigDecimal runCap) {
        this.guard = guard;
        this.commit = commit;
        this.props = props;
        this.jdbc = jdbc;
        this.llmFactory = llmFactory;
        this.catalogueService = catalogueService;
        this.usageLogService = usageLogService;
        this.ledger = ledger;
        this.pipelineService = pipelineService;
        this.learnedLibrary = learnedLibrary;
        this.resultsDir = resultsDir;
        this.privateSeedDir = privateSeedDir;
        this.noticeCap = noticeCap;
        this.runCap = runCap;
    }

    /**
     * Commit check, models, prices, database, catalogue and agents. Fails the test (no call made) when a check refuses
     * the run. {@code extraModels} are further models the caller will bill (e.g. the triage model); each must be priced.
     */
    static V7LiveRig create(String apiKey, LiveRunGuard guard, List<String> extraModels) throws Exception {
        // ---- cost safeguards first: no call is made when any of these refuses the run
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
        // a writer on the gemini provider falls back to GEMINI_MODEL when its own model is not set
        String modelNewIssue = guard.model("LLM_MODEL_NEW_ISSUE", props.writerModel("new_issue", props.getLlm().getModelNewIssue()));
        String modelHighStakes = guard.model("LLM_MODEL_HIGH_STAKES", props.writerModel("high_stakes", props.getLlm().getModelHighStakes()));
        List<String> unset = new ArrayList<>();
        if (modelMatching == null) unset.add("LLM_MODEL_MATCHING");
        if (modelPartial == null) unset.add("LLM_MODEL_PARTIAL_DRAFTING");
        if (modelNewIssue == null) unset.add("LLM_MODEL_NEW_ISSUE");
        if (modelHighStakes == null) unset.add("LLM_MODEL_HIGH_STAKES");
        if (!unset.isEmpty()) {
            fail("REFUSED: set " + String.join(", ", unset) + " (no model names are built into the runner)");
        }
        for (String agent : List.of("new_issue", "high_stakes")) {
            String p = props.getLlm().providerForAgent(agent);
            if ("gemini".equals(p) && (props.getLlm().getGemini().getApiKey() == null || props.getLlm().getGemini().getApiKey().isBlank())) {
                fail("REFUSED: the " + agent + " writer runs on gemini but GEMINI_API_KEY is not set");
            }
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
        // shortlist title pass: LLM_MODEL_SHORTLIST, else the matching model
        props.getLlm().setModelShortlist(guard.model("LLM_MODEL_SHORTLIST", props.getLlm().getModelShortlist()));
        String modelShortlist = props.getLlm().shortlistModel();

        // MATCHING_SHORTLIST_LOG_IDS=true: the shortlisted card ids (ids only) are logged at INFO for each notice
        String logIds = System.getenv("MATCHING_SHORTLIST_LOG_IDS");
        if (logIds != null && !logIds.isBlank()) props.getMatching().getShortlist().setLogIds(Boolean.parseBoolean(logIds.trim()));
        System.out.printf("shortlist card ids at INFO: %s%n", props.getMatching().getShortlist().isLogIds());

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
        System.out.printf("models: matching=%s partial=%s new_issue=%s:%s high_stakes=%s:%s shortlist=%s (ai titles %s)%n",
                modelMatching, modelPartial, props.getLlm().providerForAgent("new_issue"), modelNewIssue,
                props.getLlm().providerForAgent("high_stakes"), modelHighStakes, modelShortlist,
                props.getMatching().getShortlist().isAiTitles());

        // an unpriced model would make every cost cap blind: refuse instead of warning
        ApiUsageLogService pricingCheck = new ApiUsageLogService(null, props);
        List<String> priced = new ArrayList<>(List.of(modelMatching, modelPartial, modelNewIssue, modelHighStakes, modelShortlist));
        if (extraModels != null) priced.addAll(extraModels);
        for (String m : priced) {
            if (!pricingCheck.hasPrice(m)) {
                fail("REFUSED: no price configured for model " + m + " under noticedesk.pricing.models in application.yml");
            }
        }
        for (String agent : List.of("matching", "partial_drafting", "new_issue", "high_stakes", AppProperties.Llm.SHORTLIST)) {
            System.out.printf("%s: effort=%s max_tokens=%d retry_max_tokens=%d%n", agent,
                    props.getLlm().effortForAgent(agent),
                    props.maxTokensForAgent(agent, false), props.maxTokensForAgent(agent, true));
        }

        ObjectMapper om = new ObjectMapper();

        // one connection for the whole run, so the RLS tenant set below applies to every insert
        org.springframework.jdbc.datasource.SingleConnectionDataSource ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                "jdbc:postgresql://127.0.0.1:5432/noticedesk_dev", "noticedesk", "noticedesk", true);
        ds.setDriverClassName("org.postgresql.Driver");
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        BenchNoticeRows.setTenant(jdbc.getJdbcTemplate(), TENANT);
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

        // learned library ($PRIVATE_SEED_DIR/learned, separate from the catalogue): new-issue writer output for an
        // issue with no card is saved there as a provisional entry; approved entries reach the matcher only with
        // CATALOGUE_INCLUDE_LEARNED=true
        LearnedLibrary learnedLibrary = new LearnedLibrary(props, om);
        learnedLibrary.setSourceRun(Optional.ofNullable(LiveRunGuard.gitHead()).orElse(commit.head()));
        System.out.println(learnedLibrary.load().line() + (privateSeedDir == null || privateSeedDir.isBlank()
                ? " (PRIVATE_SEED_DIR unset: nothing is saved)" : "") + "; shown to the matcher: "
                + learnedLibrary.cardsForMatching().size() + " (include-learned " + learnedLibrary.includeLearned() + ")");
        catalogueService.setLearnedLibrary(learnedLibrary);
        System.out.println(PrivateSetSupport.catalogueLine(catalogueService));

        ApiUsageLogService usageLogService = new ApiUsageLogService(jdbc, props);
        RunCostLedger ledger = RunCostLedger.attach(usageLogService, System.out);
        guard.attach(usageLogService);

        IssueMatchingAgent matchingAgent = new IssueMatchingAgent(llmFactory, catalogueService, usageLogService, props, jdbc, om);
        // REQUIRED for the same reason as setCatalogueService below: without it the shortlist uses labels only
        matchingAgent.setShortlistTitleAgent(new ShortlistTitleAgent(props, om, usageLogService, llmFactory));
        PartialDraftingAgent partialAgent = new PartialDraftingAgent(props, om, usageLogService, llmFactory);
        NewIssueDraftingAgent newIssueAgent = new NewIssueDraftingAgent(props, om, usageLogService, llmFactory);
        HighStakesDraftingAgent highStakesAgent = new HighStakesDraftingAgent(props, om, usageLogService, llmFactory);
        // one call per notice on the partial-drafting model: card-specific template variables from the notice text
        TemplateVariableAgent variableAgent = new TemplateVariableAgent(props, om, usageLogService, llmFactory, catalogueService);

        IndianKanoonClient ikClient = new IndianKanoonClient(om);
        CitationVerificationAgent citationAgent = new CitationVerificationAgent(om, props, usageLogService, jdbc, ikClient);

        TemplateFillService fillService = new TemplateFillService(jdbc.getJdbcTemplate(), props, om);
        fillService.setLearnedLibrary(learnedLibrary);
        ReplyAssemblyService assemblyService = new ReplyAssemblyService(jdbc.getJdbcTemplate(), props, om);
        DraftCheckService checkService = new DraftCheckService(jdbc);

        V7DraftingPipelineService pipelineService = new V7DraftingPipelineService(
                matchingAgent, jdbc, om, fillService, assemblyService, checkService,
                partialAgent, newIssueAgent, highStakesAgent, citationAgent, variableAgent, props, usageLogService
        );
        // REQUIRED: the pipeline is built by hand here, so Spring's setter injection does not run; without this call
        // the post-matching stage correction (and the code defect card lookup) silently never runs in live tests
        pipelineService.setCatalogueService(catalogueService);
        pipelineService.setLearnedLibrary(learnedLibrary);

        File resultsDir = new File("../testbench/results");
        if (!resultsDir.exists() && !resultsDir.mkdirs()) {
            resultsDir = new File("testbench/results");
            resultsDir.mkdirs();
        }
        return new V7LiveRig(guard, commit, props, jdbc, llmFactory, catalogueService, usageLogService, ledger, pipelineService,
                learnedLibrary, resultsDir, privateSeedDir, noticeCap, runCap);
    }

    /**
     * Runs the v7 pipeline on one notice text and prints its report. {@code extraLines} are printed right after the
     * notice heading (the PDF runner's "OCR:" / "Triage:" lines); {@code preNoticeInr} is spend on this notice before
     * the pipeline (OCR, triage), added to its total.
     */
    NoticeOutcome runNotice(String noticeId, String label, String docType, String ocr, String expectedStatus,
                            List<String> extraLines, BigDecimal preNoticeInr) throws Exception {
        BigDecimal pre = preNoticeInr != null ? preNoticeInr : BigDecimal.ZERO;
        // the notice cap covers what this notice already spent before the pipeline (OCR, triage)
        props.getLlm().setMaxCostInrPerNotice(noticeCap.subtract(pre).max(BigDecimal.ZERO).doubleValue());

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
        noticeMap.put("tenant_id", TENANT);
        noticeMap.put("document_type", docType);
        if (ref != null) noticeMap.put("notice_number", ref);
        if (date != null) noticeMap.put("issue_date", date);
        if (section != null) noticeMap.put("section", section);

        UUID noticeUuid = noticeUuid(noticeId);
        // issue_matches.notice_id references notices: create the synthetic notice row first. A notice whose
        // GSTIN is a placeholder gets the synthetic bench GSTIN for its DB rows only; the draft itself sees null.
        String rowGstin = gstin != null ? gstin : BENCH_PLACEHOLDER_GSTIN;
        BenchNoticeRows.ensureNotice(jdbc.getJdbcTemplate(), TENANT, noticeUuid, noticeId, rowGstin, clientName, docType, fy);

        DraftingAgent.DraftingInput input = new DraftingAgent.DraftingInput(
                noticeUuid, "formal", null, clientName, null, null, "Regular", gstin, state,
                "CGST Act", fy, null, noticeMap, Map.of(), ocr, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, null
        );

        System.out.printf("%n--- Calls for %s ---%n", noticeId);
        int callCheckpoint = ledger.size();
        // issue_matches rows written from here on are this run's (the notice id repeats across runs)
        java.sql.Timestamp runStart = jdbc.getJdbcTemplate().queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class);
        DraftingPipelineResult result;
        try {
            result = pipelineService.runPipeline(input, TENANT, noticeUuid);
        } catch (MatchingFailedException e) {
            // no scaffold draft: the notice is marked failed and the run moves on
            BigDecimal spent = ledger.totalInrSince(callCheckpoint).add(pre);
            System.out.printf("Notice %s: FAILED - %s | cost %.4f INR%n", noticeId, e.getMessage(), spent.doubleValue());
            if (e.costCap()) {
                // the matching call did not fit the remaining budget: later notices would not fit either
                System.out.println("RUN STOPPED: matching call refused by the cost cap (no call made); remaining notices not run.");
            }
            return new NoticeOutcome("FAILED (" + e.getMessage() + ")", spent, e.costCap(), null, null, null);
        } catch (LlmFatalException e) {
            BigDecimal spent = ledger.totalInrSince(callCheckpoint).add(pre);
            return new NoticeOutcome("ABORTED", spent, true, e.getMessage(), null, null);
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

        double noticeTotalInr = pre.doubleValue() + matchingInr + variablesInr + writerInr + ikInr;

        // Extract flags (issue-level flags come as "<FLAG>#<issue no>:<severity>", e.g. DEFECT_ADDED_BY_CODE#3:info)
        List<String> allFlags = V7LivePipelineRunnerTest.flagList(result.checkResults());

        // Extract citation statuses
        List<String> citStatuses = new ArrayList<>();
        if (result.citations() != null) {
            for (VerifiedCitation vc : result.citations()) {
                citStatuses.add(vc.caseName() + " -> " + vc.status());
            }
        }

        // Render full draft text and save it to testbench/results/<noticeId>_v7_draft.txt
        String fullDraftText = V7LivePipelineRunnerTest.renderDraft(noticeId, result);
        File draftFile = new File(resultsDir, noticeId + "_v7_draft.txt");
        Files.writeString(draftFile.toPath(), fullDraftText, StandardCharsets.UTF_8);

        System.out.printf("%n--- Notice: %s (%s) ---%n", noticeId, label);
        if (extraLines != null) extraLines.forEach(System.out::println);
        System.out.printf("Expected Status (Answer Key): %s%n", expectedStatus);
        System.out.printf("Matching Step: In=%d, Out=%d | INR=%.4f%n", matchingInTokens, matchingOutTokens, matchingInr);
        System.out.printf("Variables Step: INR=%.4f%n", variablesInr);
        System.out.printf("Writer Step: INR=%.4f%n", writerInr);
        System.out.printf("IndianKanoon Step: INR=%.4f%n", ikInr);
        System.out.printf("Total Notice Cost: %.4f INR%n", noticeTotalInr);
        long blocking = result.checkResults() == null ? 0 : result.checkResults().stream()
                .filter(c -> !c.passed() && "block".equals(c.severity())).count();
        System.out.printf("Citation Statuses: %s%n", citStatuses.isEmpty() ? "None" : citStatuses);
        System.out.printf("Shortlist: %s%n", V7LivePipelineRunnerTest.shortlistLine(result.checkResults()));
        System.out.printf("Matched issues: %s%n", V7LivePipelineRunnerTest.matchedIssuesLine(jdbc.getJdbcTemplate().queryForList(
                "SELECT issue_no, status, card_ids::text AS card_ids FROM issue_matches WHERE notice_id = CAST(? AS UUID)"
                        + " AND created_at >= ? ORDER BY issue_no", noticeUuid.toString(), runStart)));
        System.out.printf("Flags: %s%n", allFlags);
        learnedLines(result).forEach(System.out::println);
        System.out.printf("Saved draft to: %s%n", draftFile.getAbsolutePath());

        if ("notice_10_nomatch_anti_profiteering.json".equals(label)) {
            System.out.println("\n=== FULL DRAFT FOR NOTICE_10 ===");
            System.out.println(fullDraftText);
            System.out.println("=== END FULL DRAFT FOR NOTICE_10 ===\n");
        }
        return new NoticeOutcome("DRAFTED (" + blocking + " blocking checks failed)", BigDecimal.valueOf(noticeTotalInr),
                false, null, result, draftFile);
    }

    static UUID noticeUuid(String noticeId) {
        return UUID.nameUUIDFromBytes(noticeId.getBytes(StandardCharsets.UTF_8));
    }

    /** One "Learned: saved LRN-xxxx for issue #N" line per AI section the pipeline saved to the learned library. */
    static List<String> learnedLines(DraftingPipelineResult result) {
        List<String> out = new ArrayList<>();
        if (result == null || result.aiIssueSections() == null) return out;
        for (var r : result.aiIssueSections()) {
            Object id = r.outputJson() != null ? r.outputJson().get(V7DraftingPipelineService.OUTPUT_LEARNED_ENTRY_ID) : null;
            if (id != null) out.add("Learned: saved " + id + " for issue #" + r.issueNo());
        }
        return out;
    }

    /** Prints the run summary; fails the test when the run was aborted. */
    void printSummary(String title, Map<String, String> statusByNotice, BigDecimal totalInr, String abortReason) {
        System.out.printf("%n=== RUN SUMMARY (commit %s%s) ===%n", commit.head(), guard.smoke() ? ", SMOKE" : "");
        statusByNotice.forEach((id, st) -> System.out.println("  " + id + ": " + st));
        System.out.printf("Total %s Execution Cost: %.4f INR (run cap INR %s)%n", title, totalInr.doubleValue(), runCap.toPlainString());
        System.out.printf("Billed model calls: %d (failed: %d, unpriced: %d) | model cost: %.4f INR%n",
                ledger.size(), ledger.failedCalls(), ledger.unpricedCalls(), ledger.totalInr().doubleValue());
        System.out.println(learnedLibrary.counts().line());
        if (abortReason != null) {
            System.err.println("LIVE RUN ABORTED: " + abortReason);
            fail("LIVE RUN ABORTED: " + abortReason);
        }
    }
}
