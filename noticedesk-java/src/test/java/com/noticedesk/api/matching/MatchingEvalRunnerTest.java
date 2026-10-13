package com.noticedesk.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noticedesk.api.agent.IssueMatchingAgent;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue;
import com.noticedesk.api.agent.IssueMatchingAgent.MatchingResult;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.service.llm.LlmFactory;
import com.noticedesk.api.service.matching.CatalogueService;
import com.noticedesk.api.service.llm.LlmFatalException;
import com.noticedesk.api.testsupport.LiveRunGuard;
import com.noticedesk.api.testsupport.PrivateSetSupport;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

class MatchingEvalRunnerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    record WrongMatchInfo(String noticeId, String expected, String got, String why) {}

    @Test
    void runMatchingEvaluationBenchmark() throws Exception {
        String enableE2e = System.getenv("ENABLE_E2E_TESTS");
        if (!"true".equalsIgnoreCase(enableE2e) && !"true".equalsIgnoreCase(System.getProperty("ENABLE_E2E_TESTS"))) {
            System.out.println("Skipping MatchingEvalRunnerTest (ENABLE_E2E_TESTS not set to true)");
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

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String matchingModel = System.getenv("LLM_MODEL_MATCHING");
        if (matchingModel == null || matchingModel.isBlank()) {
            System.out.println("Skipping MatchingEvalRunnerTest (LLM_MODEL_MATCHING not set)");
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("Skipping MatchingEvalRunnerTest (ANTHROPIC_API_KEY missing)");
            return;
        }

        // pricing, effort and max_tokens come from application.yml (+ env overrides)
        AppProperties props = TestAppProperties.fromApplicationYml();
        props.getLlm().setProviderPrimary("anthropic");
        props.getLlm().getAnthropic().setApiKey(apiKey);
        String wsId = System.getenv("ANTHROPIC_WORKSPACE_ID");
        if (wsId != null && !wsId.isBlank()) {
            props.getLlm().getAnthropic().setWorkspaceId(wsId);
        }
        props.getLlm().setModelMatching(matchingModel);
        props.getMatching().setAllowDraftCards(true); // Allow dev draft cards for test bench evaluation

        if (!new ApiUsageLogService(null, props).hasPrice(matchingModel)) {
            // an unpriced model would make the cost caps blind
            fail("REFUSED: no price configured for model " + matchingModel + " under noticedesk.pricing.models in application.yml");
        }
        guard.printSettings(System.out);
        System.out.println("Matching effort=" + props.getLlm().effortForAgent("matching")
                + " max_tokens=" + props.getLlm().maxTokensForAgent("matching", false)
                + " retry_max_tokens=" + props.getLlm().maxTokensForAgent("matching", true));

        org.springframework.jdbc.datasource.DriverManagerDataSource ds = new org.springframework.jdbc.datasource.DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://127.0.0.1:5432/noticedesk_dev");
        ds.setUsername("noticedesk");
        ds.setPassword("noticedesk");
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(ds);

        LlmFactory llmFactory = new LlmFactory(props);
        CatalogueService catalogueService = new CatalogueService(jdbcTemplate, props, objectMapper);
        ApiUsageLogService usageLogService = new ApiUsageLogService(null, props);
        RunCostLedger ledger = RunCostLedger.attach(usageLogService, System.out);
        guard.attach(usageLogService);
        this.guard = guard;
        this.pricing = usageLogService;
        IssueMatchingAgent agent = new IssueMatchingAgent(llmFactory, catalogueService, usageLogService, props, null, objectMapper);

        // The private catalogue (PRIVATE_SEED_DIR set) must never be evaluated next to the dummy cards
        String privateSeedDir = envOrProperty("PRIVATE_SEED_DIR");
        String dummyCardRefusal = PrivateSetSupport.dummyCardRefusal(jdbcTemplate, privateSeedDir);
        if (dummyCardRefusal != null) {
            fail(dummyCardRefusal);
        }
        System.out.println(PrivateSetSupport.catalogueLine(catalogueService));

        // MATCHING_EVAL_SETS: both (default) | dummy | private. Dummy set: the committed testbench (fake data).
        // Private set: $PRIVATE_SEED_DIR/notices/*.json (real data kept outside the repo), or only the files
        // named in MATCHING_EVAL_NOTICES. They are evaluated and reported separately.
        String sets = Optional.ofNullable(envOrProperty("MATCHING_EVAL_SETS")).orElse("both").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("both", "dummy", "private").contains(sets)) {
            fail("REFUSED: MATCHING_EVAL_SETS must be both, dummy or private (got '" + sets + "')");
        }
        boolean runDummy = !"private".equals(sets);
        boolean runPrivate = !"dummy".equals(sets);
        System.out.println("MATCHING_EVAL_SETS=" + sets);

        // MATCHING_EVAL_SHOW_WHY=true: the model's "why" and not_this_if_checked lines are printed per issue for the
        // private set too (the private notices are synthetic: fictional party, placeholder GSTIN). The notice text
        // is never printed, and the results JSON keeps metrics only either way.
        showWhy = "true".equalsIgnoreCase(envOrProperty("MATCHING_EVAL_SHOW_WHY"));
        System.out.println("MATCHING_EVAL_SHOW_WHY=" + showWhy
                + (showWhy ? " (per-issue reasons printed for every set, private included; notice text still withheld)" : ""));

        // $PRIVATE_SEED_DIR/defect_cards.txt (optional, one card id per line): the expected issues whose card is listed
        // count as expected defect issues; without the file the expected-defect count is skipped
        defectCardIds = PrivateSetSupport.defectCardIds(privateSeedDir);
        System.out.println(defectCardIds == null ? "Defect card list: none (defect_cards.txt absent; expected-defect count skipped)"
                : "Defect card list: " + defectCardIds.size() + " card id(s) from defect_cards.txt");

        List<Resource> dummyNotices = new ArrayList<>();
        if (runDummy) {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Map<String, Resource> uniqueResources = new TreeMap<>();
            for (Resource res : resolver.getResources("classpath*:testbench/notices/*.json")) {
                uniqueResources.putIfAbsent(res.getFilename(), res);
            }
            dummyNotices.addAll(uniqueResources.values());
        }

        List<Resource> privateNotices = new ArrayList<>();
        String noticesEnv = envOrProperty("MATCHING_EVAL_NOTICES");
        if (runPrivate) {
            if (privateSeedDir == null || privateSeedDir.isBlank()) {
                if ("private".equals(sets)) {
                    fail("REFUSED: MATCHING_EVAL_SETS=private but PRIVATE_SEED_DIR is not set");
                }
                if (noticesEnv != null && !noticesEnv.isBlank()) {
                    fail("REFUSED: MATCHING_EVAL_NOTICES is set but PRIVATE_SEED_DIR is not");
                }
                System.out.println("Private notice set skipped (PRIVATE_SEED_DIR not set)");
            } else if (noticesEnv != null && !noticesEnv.isBlank()) {
                for (String name : noticesEnv.split(",")) {
                    String n = name.trim();
                    if (n.isEmpty()) continue;
                    Resource res = PrivateSetSupport.privateNotice(privateSeedDir, n);
                    if (res == null) {
                        fail("REFUSED: private notice file not found: " + n);
                    }
                    privateNotices.add(res);
                }
                System.out.println("Private notice set (MATCHING_EVAL_NOTICES): " + privateNotices.size() + " file(s)");
            } else {
                privateNotices.addAll(PrivateSetSupport.privateNotices(privateSeedDir));
                System.out.println("Private notice set: " + privateNotices.size() + " file(s)");
            }
        }

        if (guard.smoke()) {
            if ("private".equals(sets)) {
                // smoke on the private set: its first notice only (file name printed: it carries no notice content)
                if (!privateNotices.isEmpty()) {
                    privateNotices = List.of(privateNotices.get(0));
                    System.out.println("LIVE_SMOKE=true: only private notice " + privateNotices.get(0).getFilename() + " is run");
                }
            } else {
                if (!dummyNotices.isEmpty()) {
                    dummyNotices = List.of(dummyNotices.get(0));
                }
                privateNotices = List.of();
                System.out.println("LIVE_SMOKE=true: only " + (dummyNotices.isEmpty() ? "(no dummy notice)" : dummyNotices.get(0).getFilename())
                        + " is run; private set skipped");
            }
        }

        BigDecimal[] spentInr = {BigDecimal.ZERO};
        ObjectNode dummySummary = runDummy ? evaluateSet("dummy", dummyNotices, false, agent, ledger, spentInr) : null;
        ObjectNode privateSummary = privateNotices.isEmpty() || abortReason != null ? null
                : evaluateSet("private", privateNotices, true, agent, ledger, spentInr);
        System.out.printf("Total billed calls: %d (failed: %d, unpriced: %d) | Total cost: %.4f INR (run cap INR %s, commit %s%s)%n",
                ledger.size(), ledger.failedCalls(), ledger.unpricedCalls(), ledger.totalInr().doubleValue(),
                guard.maxRunCostInr().toPlainString(), commit.head(), guard.smoke() ? ", SMOKE" : "");

        // Write timestamped JSON report to repo root testbench/results/
        File resultsDir = new File("../testbench/results");
        if (!resultsDir.exists() && !resultsDir.mkdirs()) {
            resultsDir = new File("testbench/results");
            resultsDir.mkdirs();
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File reportFile = new File(resultsDir, "eval_matching_" + timestamp + ".json");

        ObjectNode summaryReport = objectMapper.createObjectNode();
        summaryReport.put("timestamp", timestamp);
        summaryReport.put("model", matchingModel);
        summaryReport.put("sets", sets);
        if (dummySummary != null) {
            summaryReport.set("dummy", dummySummary);
        }
        if (privateSummary != null) {
            summaryReport.set("private", privateSummary);   // notice IDs + metrics only
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile, summaryReport);
        System.out.println("Evaluation report written to: " + reportFile.getAbsolutePath());
        if (abortReason != null) {
            System.err.println("LIVE RUN ABORTED: " + abortReason);
            fail("LIVE RUN ABORTED: " + abortReason);
        }
    }

    private LiveRunGuard guard;
    private ApiUsageLogService pricing;
    /** Set when a billing / auth error or the consecutive-failure limit stopped the run. */
    private String abortReason;
    /** {@code MATCHING_EVAL_SHOW_WHY}: print the model's reasons per issue for the private set too. */
    private boolean showWhy;
    /** Card ids of defect cards ({@code $PRIVATE_SEED_DIR/defect_cards.txt}), or null when the file is absent. */
    private Set<String> defectCardIds;

    private static String envOrProperty(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) v = System.getProperty(name);
        return v;
    }

    /**
     * Card-level outcome of one notice against its (de-duplicated) answer key, see {@link #scoreCards}.
     * {@code expected} is the number of expected issues after de-duplication.
     */
    record CardScore(int expected, int correct, int primaryRightStatusWrong, int rightCardInAlternates, int wrongCard,
                     int missed, int extra, List<WrongMatchInfo> wrong) {
        boolean allCorrect() {
            return correct == expected && extra == 0;
        }
    }

    static final String KIND_PRIMARY_RIGHT_STATUS_WRONG = "primary card right, status wrong";
    static final String KIND_RIGHT_CARD_IN_ALTERNATES = "right card only in alternates";
    static final String KIND_WRONG = "wrong";
    static final String KIND_MISSED = "missed";

    /**
     * Card-level accuracy against the PRIMARY card only (a shortlist counts for nothing). The answer key's
     * expected issues are first de-duplicated by their card_ids set (several keys list interest / penalty as a
     * separate issue on the main issue's card; those are one ground under the splitting rules). Each
     * expected issue is then paired with at most one returned issue, in this order:
     * <ol>
     *   <li>correct: same status and the returned PRIMARY card is one of the expected ids (status "none" needs
     *       no card);</li>
     *   <li>primary card right, status wrong: the returned primary is an expected id, the status differs;</li>
     *   <li>right card only in alternates: an expected id is among the returned alternates (any status);</li>
     *   <li>wrong: an unused returned issue remains (same status first) but neither its primary nor its
     *       alternates is an expected id;</li>
     *   <li>missed: no unused returned issue remains.</li>
     * </ol>
     * Returned issues beyond the de-duplicated expected count are extra. For a private notice only statuses
     * and card ids are reported; the model's explanation is withheld.
     */
    static CardScore scoreCards(String noticeId, JsonNode answerKey, List<MatchedIssue> returnedIssues, boolean isPrivate) {
        return scoreCards(noticeId, answerKey, returnedIssues, isPrivate, false);
    }

    /**
     * As {@link #scoreCards(String, JsonNode, List, boolean)}; with {@code showWhy} the model's "why" and its
     * not_this_if_checked lines are reported for a private notice too ({@code MATCHING_EVAL_SHOW_WHY=true}).
     */
    static CardScore scoreCards(String noticeId, JsonNode answerKey, List<MatchedIssue> returnedIssues, boolean isPrivate,
                                boolean showWhy) {
        List<MatchedIssue> returned = returnedIssues == null ? List.of() : returnedIssues;
        JsonNode expectedNode = answerKey.path("issues");
        if (!expectedNode.isArray()) {
            return new CardScore(0, 0, 0, 0, 0, 0, 0, List.of());
        }
        List<JsonNode> expectedIssues = dedupeExpected(expectedNode);
        int expectedCount = expectedIssues.size();
        boolean[] used = new boolean[returned.size()];
        int correct = 0, primaryRightStatusWrong = 0, inAlternates = 0, wrongCard = 0, missed = 0;
        List<Integer> unresolved = new ArrayList<>();

        for (int e = 0; e < expectedIssues.size(); e++) {
            JsonNode exp = expectedIssues.get(e);
            String expStatus = expectedStatus(exp);
            List<String> expCards = cardIds(exp.path("card_ids"));
            int idx = -1;
            for (int r = 0; r < returned.size() && idx < 0; r++) {
                if (used[r]) continue;
                MatchedIssue got = returned.get(r);
                if (!expStatus.equalsIgnoreCase(got.status())) continue;
                if ("none".equals(expStatus) || contains(expCards, got.primaryCardId())) idx = r;
            }
            if (idx >= 0) {
                used[idx] = true;
                correct++;
            } else {
                unresolved.add(e);
            }
        }

        String gotAll = returned.isEmpty() ? "no issue returned"
                : returned.stream().map(MatchingEvalRunnerTest::gotColumn).collect(java.util.stream.Collectors.joining(", "));
        boolean withhold = isPrivate && !showWhy;
        String withheld = "(withheld: private notice)";
        List<WrongMatchInfo> wrong = new ArrayList<>();
        for (int e : unresolved) {
            JsonNode exp = expectedIssues.get(e);
            String expStatus = expectedStatus(exp);
            List<String> expCards = cardIds(exp.path("card_ids"));
            String kind;
            int idx = firstUnused(returned, used, got -> contains(expCards, got.primaryCardId()));
            if (idx >= 0) {
                kind = KIND_PRIMARY_RIGHT_STATUS_WRONG;
                primaryRightStatusWrong++;
            } else {
                idx = firstUnused(returned, used, got -> overlaps(expCards, got.alternateCardIds()));
                if (idx >= 0) {
                    kind = KIND_RIGHT_CARD_IN_ALTERNATES;
                    inAlternates++;
                } else {
                    idx = firstUnused(returned, used, got -> expStatus.equalsIgnoreCase(got.status()));
                    if (idx < 0) idx = firstUnused(returned, used, got -> true);
                    if (idx >= 0) {
                        kind = KIND_WRONG;
                        wrongCard++;
                    } else {
                        kind = KIND_MISSED;
                        missed++;
                    }
                }
            }
            String why;
            if (idx >= 0) {
                used[idx] = true;
                why = withhold ? withheld : reasonOf(returned.get(idx), showWhy);
            } else {
                why = returned.isEmpty() ? "No issue returned" : withhold ? withheld : reasonOf(returned.get(0), showWhy);
            }
            wrong.add(new WrongMatchInfo(noticeId, expStatus + " " + cardList(expCards) + " [" + kind + "]", gotAll, why));
        }
        int extra = Math.max(0, returned.size() - expectedCount);
        if (extra > 0) {
            wrong.add(new WrongMatchInfo(noticeId, expectedCount + " issue(s) [extra returned: " + extra + "]", gotAll,
                    withhold ? withheld : "More issues returned than the answer key expects"));
        }
        return new CardScore(expectedCount, correct, primaryRightStatusWrong, inAlternates, wrongCard, missed, extra, wrong);
    }

    /**
     * The answer key's expected issues without duplicates: two expected issues with the same (non-empty)
     * card_ids set are one ground, the first one is kept. Expected issues without a card (status "none") are
     * never merged.
     */
    static List<JsonNode> dedupeExpected(JsonNode expectedIssues) {
        List<JsonNode> out = new ArrayList<>();
        Set<Set<String>> seen = new HashSet<>();
        for (JsonNode exp : expectedIssues) {
            Set<String> key = new TreeSet<>();
            for (String c : cardIds(exp.path("card_ids"))) key.add(c.toLowerCase(Locale.ROOT));
            if (!key.isEmpty() && !seen.add(key)) continue;
            out.add(exp);
        }
        return out;
    }

    private static String expectedStatus(JsonNode exp) {
        return exp.path("status").asText("").trim().toLowerCase(Locale.ROOT);
    }

    private static int firstUnused(List<MatchedIssue> returned, boolean[] used, java.util.function.Predicate<MatchedIssue> test) {
        for (int r = 0; r < returned.size(); r++) {
            if (!used[r] && test.test(returned.get(r))) return r;
        }
        return -1;
    }

    /** "Got" column entry of one returned issue: kind, status, primary card and (when any) the alternates. */
    static String gotColumn(MatchedIssue i) {
        String s = i.kind() + ":" + i.status() + cardList(i.cardIds());
        if (i.alternateCardIds() != null && !i.alternateCardIds().isEmpty()) s += " alt" + cardList(i.alternateCardIds());
        return s;
    }

    /**
     * The model's reason for one issue as reported in WRONG MATCH DETAILS: the "why" text, plus (with
     * {@code showWhy}) its not_this_if_checked lines.
     */
    static String reasonOf(MatchedIssue i, boolean showWhy) {
        String why = String.valueOf(i.why());
        if (!showWhy) return why;
        return "issue " + i.issueNo() + " why: " + why + " | not_this_if: " + notThisIfLine(i);
    }

    private static String notThisIfLine(MatchedIssue i) {
        return i.notThisIfChecked() == null || i.notThisIfChecked().isEmpty() ? "(none)" : String.join(" ; ", i.notThisIfChecked());
    }

    /**
     * The per-issue reason lines printed to the console when {@code MATCHING_EVAL_SHOW_WHY=true}: one "why" line
     * and one not_this_if line per issue, each prefixed with the notice id and issue number. Never the notice text.
     */
    static List<String> reasonLines(String noticeId, List<MatchedIssue> issues) {
        List<String> out = new ArrayList<>();
        if (issues == null) return out;
        for (MatchedIssue i : issues) {
            String prefix = "  " + noticeId + " issue " + i.issueNo() + " [" + gotColumn(i) + "]";
            out.add(prefix + " why: " + String.valueOf(i.why()).replace('\n', ' '));
            out.add(prefix + " not_this_if: " + notThisIfLine(i).replace('\n', ' '));
        }
        return out;
    }

    /** Returned issues of kind {@code defect}. */
    static int defectIssuesReturned(List<MatchedIssue> issues) {
        if (issues == null) return 0;
        int n = 0;
        for (MatchedIssue i : issues) if (i.isDefect()) n++;
        return n;
    }

    /**
     * Expected (de-duplicated) issues of the answer key whose card is one of {@code defectCardIds}; -1 when there
     * is no defect card list (the count is skipped).
     */
    static int defectIssuesExpected(JsonNode answerKey, Set<String> defectCardIds) {
        if (defectCardIds == null) return -1;
        JsonNode expectedNode = answerKey.path("issues");
        if (!expectedNode.isArray()) return 0;
        int n = 0;
        for (JsonNode exp : dedupeExpected(expectedNode)) {
            boolean defect = false;
            for (String id : cardIds(exp.path("card_ids"))) {
                if (defectCardIds.contains(id.toUpperCase(Locale.ROOT))) defect = true;
            }
            if (defect) n++;
        }
        return n;
    }

    private static List<String> cardIds(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) out.add(n.asText().trim());
        } else if (node.isTextual()) {
            out.add(node.asText().trim());
        }
        return out;
    }

    private static boolean contains(List<String> expected, String got) {
        if (got == null) return false;
        for (String e : expected) {
            if (e.equalsIgnoreCase(got.trim())) return true;
        }
        return false;
    }

    private static boolean overlaps(List<String> expected, List<String> got) {
        if (got == null) return false;
        for (String g : got) {
            if (contains(expected, g)) return true;
        }
        return false;
    }

    private static String cardList(List<String> ids) {
        return ids == null ? "[]" : ids.toString();
    }

    /**
     * Evaluates one notice set. The returned JSON holds only notice IDs and metrics; for the private
     * set nothing from the notice text or the model's explanation is printed or stored.
     */
    ObjectNode evaluateSet(String setName, List<Resource> noticeList, boolean isPrivate,
                           IssueMatchingAgent agent, RunCostLedger ledger, BigDecimal[] spentInr) throws Exception {

        int totalNotices = 0;
        int errorCallsCount = 0;
        int wrongFullMatchCount = 0;
        int expectedFullMatchCount = 0;
        int multiFullMatchCount = 0;
        int correctPartialOrNoneCount = 0;
        int expectedPartialOrNoneCount = 0;

        int totalExpectedFacts = 0;
        int matchedFactsCount = 0;

        int totalExpectedParas = 0;
        int coveredParasCount = 0;
        int issueCountChecked = 0;
        int issueCountMatched = 0;
        List<String> issueCountMismatches = new ArrayList<>();

        int totalCalls = 0;
        int cacheHitCalls = 0;
        int totalCacheReadTokensCall2Plus = 0;

        // card-level accuracy (status + PRIMARY card id per de-duplicated expected issue)
        int cardIssuesExpected = 0;
        int cardIssuesCorrect = 0;
        int cardPrimaryRightStatusWrong = 0;
        int cardRightInAlternates = 0;
        int cardIssuesWrong = 0;
        int cardIssuesMissed = 0;
        int cardIssuesExtra = 0;
        int noticesAllIssuesCorrect = 0;

        // v3 defect issues: returned (kind = defect) and expected (card in defect_cards.txt; skipped without the file)
        int defectReturned = 0;
        int defectExpected = 0;

        BigDecimal startInr = spentInr[0];      // the INR cap applies across both sets
        BigDecimal totalCostInr = startInr;
        final BigDecimal INR_CAP = guard != null ? guard.maxRunCostInr() : LiveRunGuard.DEFAULT_MAX_RUN_COST_INR;

        List<ObjectNode> noticeEvalResults = new ArrayList<>();
        List<WrongMatchInfo> wrongMatches = new ArrayList<>();

        for (Resource res : noticeList) {
            if (totalCostInr.compareTo(INR_CAP) >= 0) {
                System.err.println("SAFETY CAP REACHED: Total spend " + totalCostInr + " INR reached the run cap of INR " + INR_CAP + ". Halting evaluation.");
                break;
            }

            totalNotices++;
            totalCalls++;

            String jsonText;
            try (InputStream is = res.getInputStream()) {
                jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root = objectMapper.readTree(jsonText);

            String noticeId = root.path("notice_id").asText("bench-notice");
            String docType = root.path("document_type").asText("scn_73");
            String ocrText = root.path("full_ocr_text").asText();
            JsonNode answerKey = root.path("answer_key");

            System.out.println("Notice " + noticeId + ":");
            int callCheckpoint = ledger.size();
            MatchingResult result;
            try {
                // hard cap: each attempt is refused before the call when its worst case would not fit
                BigDecimal noticeCap = guard != null ? guard.noticeCapFor(totalCostInr) : INR_CAP.subtract(totalCostInr);
                ApiUsageLogService prices = pricing != null ? pricing : new ApiUsageLogService(null, TestAppProperties.fromApplicationYml());
                NoticeCallBudget budget = new NoticeCallBudget(Integer.MAX_VALUE, noticeCap.max(BigDecimal.ZERO), prices);
                result = agent.matchNoticeWithOcrText(ocrText, docType, UUID.randomUUID(), UUID.randomUUID(), budget);
            } catch (LlmFatalException e) {
                // billing / auth error, or LIVE_MAX_CONSECUTIVE_FAILURES reached: stop the whole run now
                totalCostInr = totalCostInr.add(ledger.totalInrSince(callCheckpoint));
                abortReason = e.getMessage();
                errorCallsCount++;
                break;
            }

            // every billed call for this notice (failed attempts and retries included)
            BigDecimal noticeInr = ledger.totalInrSince(callCheckpoint);
            totalCostInr = totalCostInr.add(noticeInr);
            String whyStr = result.issues().stream().map(com.noticedesk.api.agent.IssueMatchingAgent.MatchedIssue::why).collect(java.util.stream.Collectors.joining(" ")).toLowerCase();
            // same inputs as the live pipeline: compare this hash with its matcher run when results differ
            System.out.printf("  catalogue_hash=%s issues(kind:status[primary] alt[...])=%s flags=%s cost=%.4f INR%n", result.catalogueHash(),
                    result.issues().stream().map(MatchingEvalRunnerTest::gotColumn).toList(), result.flags(), noticeInr.doubleValue());
            if (showWhy) {
                for (String line : reasonLines(noticeId, result.issues())) System.out.println(line);
            }

            if (result.flags().contains(IssueMatchingAgent.FLAG_COST_CAP)) {
                totalNotices--;
                totalCalls--;
                System.err.println("RUN STOPPED: matching call refused by the run cost cap (no call made): " + result.missingOrDoubtful());
                break;
            }

            // Requirement 1: Failed/fallback match counted as an ERROR, never as correct "none"
            if (result.flags().contains("matching_failed") || result.flags().contains("matching_parse_failed")) {
                errorCallsCount++;
                wrongMatches.add(new WrongMatchInfo(noticeId, "Successful Match", "ERROR",
                        "Call failed or returned fallback result: " + result.flags() + (isPrivate ? "" : " | detail: " + whyStr)));
                continue;
            }

            if (totalCalls > 1) {
                totalCacheReadTokensCall2Plus += result.cacheReadTokens();
            }

            if (result.cacheReadTokens() > 0) {
                cacheHitCalls++;
            }

            // Metrics evaluation against answer_key (status-level, as before)
            JsonNode expectedIssues = answerKey.path("issues");
            if (expectedIssues.isArray()) {
                if (expectedIssues.size() > 1) {
                    multiFullMatchCount++;
                }

                for (JsonNode exp : expectedIssues) {
                    String expStatus = exp.path("status").asText();

                    if ("full".equalsIgnoreCase(expStatus)) {
                        expectedFullMatchCount++;
                        boolean matchedFull = result.issues().stream().anyMatch(i -> "full".equalsIgnoreCase(i.status()));
                        if (!matchedFull) {
                            wrongFullMatchCount++;
                        }
                    } else {
                        expectedPartialOrNoneCount++;
                        boolean matchedNonFull = result.issues().stream().anyMatch(i -> expStatus.equalsIgnoreCase(i.status()));
                        if (matchedNonFull) {
                            correctPartialOrNoneCount++;
                        }
                    }

                    totalExpectedFacts++;
                    if (!result.issues().isEmpty() && result.issues().get(0).facts() != null && !result.issues().get(0).facts().isEmpty()) {
                        matchedFactsCount++;
                    }
                }
            }

            // Card-level accuracy: status AND the PRIMARY card per de-duplicated expected issue
            CardScore cards = scoreCards(noticeId, answerKey, result.issues(), isPrivate, showWhy);
            defectReturned += defectIssuesReturned(result.issues());
            if (defectCardIds != null) defectExpected += defectIssuesExpected(answerKey, defectCardIds);
            cardIssuesExpected += cards.expected();
            cardIssuesCorrect += cards.correct();
            cardPrimaryRightStatusWrong += cards.primaryRightStatusWrong();
            cardRightInAlternates += cards.rightCardInAlternates();
            cardIssuesWrong += cards.wrongCard();
            cardIssuesMissed += cards.missed();
            cardIssuesExtra += cards.extra();
            if (cards.allCorrect()) noticesAllIssuesCorrect++;
            wrongMatches.addAll(cards.wrong());

            // Issue splitting (prompt rule 4): one issue per distinct ground; consequential interest / penalty not split
            if (answerKey.has("expected_issues")) {
                issueCountChecked++;
                int expectedCount = answerKey.path("expected_issues").asInt();
                int gotCount = result.issues() != null ? result.issues().size() : 0;
                if (expectedCount == gotCount) issueCountMatched++;
                else issueCountMismatches.add(noticeId + ": expected " + expectedCount + ", got " + gotCount);
            }

            int expectedParaCount = answerKey.path("para_count").asInt(1);
            totalExpectedParas += expectedParaCount;
            if (result.paraMap() != null && !result.paraMap().isEmpty()) {
                coveredParasCount += Math.min(result.paraMap().size(), expectedParaCount);
            }

            ObjectNode evalItem = objectMapper.createObjectNode();
            evalItem.put("notice_id", noticeId);
            evalItem.put("document_type", docType);
            evalItem.put("input_tokens", result.inputTokens());
            evalItem.put("output_tokens", result.outputTokens());
            evalItem.put("cache_read_tokens", result.cacheReadTokens());
            evalItem.put("cache_write_tokens", result.cacheWriteTokens());
            evalItem.put("cost_inr", noticeInr.doubleValue());
            evalItem.put("issues_expected", cards.expected());
            evalItem.put("issues_correct_primary", cards.correct());
            evalItem.put("issues_primary_right_status_wrong", cards.primaryRightStatusWrong());
            evalItem.put("issues_right_card_in_alternates", cards.rightCardInAlternates());
            evalItem.put("issues_wrong", cards.wrongCard());
            evalItem.put("issues_missed", cards.missed());
            evalItem.put("issues_extra", cards.extra());
            evalItem.put("all_issues_correct", cards.allCorrect());
            evalItem.put("defect_issues_returned", defectIssuesReturned(result.issues()));
            if (defectCardIds != null) evalItem.put("defect_issues_expected", defectIssuesExpected(answerKey, defectCardIds));
            evalItem.set("result_flags", objectMapper.valueToTree(result.flags()));
            noticeEvalResults.add(evalItem);
        }

        int successfulCalls = totalNotices - errorCallsCount;
        // With zero successful calls nothing was measured: report N/A, never 100%.
        String wrongFullMatchRate = RunCostLedger.pct(wrongFullMatchCount, expectedFullMatchCount, successfulCalls);
        String partialNoneCorrectPct = RunCostLedger.pct(correctPartialOrNoneCount, expectedPartialOrNoneCount, successfulCalls);
        String factAccuracyPct = RunCostLedger.pct(matchedFactsCount, totalExpectedFacts, successfulCalls);
        String paraCoveragePct = RunCostLedger.pct(coveredParasCount, totalExpectedParas, successfulCalls);
        String cacheHitRatePct = RunCostLedger.pct(cacheHitCalls, successfulCalls - 1, successfulCalls);
        BigDecimal setCostInr = totalCostInr.subtract(startInr);

        System.out.println("=== MATCHING EVALUATION BENCHMARK METRICS: " + setName.toUpperCase() + " SET ===");
        System.out.printf("Total Notices Evaluated: %d (Files Count: %d)%n", totalNotices, noticeList.size());
        System.out.printf("Successful Calls: %d | Errors/Failures: %d%n", successfulCalls, errorCallsCount);
        System.out.printf("Multi-Issue Full Matches (multi_full): %d%n", multiFullMatchCount);
        System.out.println("Wrong Full-Match Rate: " + wrongFullMatchRate);
        System.out.println("Partial/None Correctly Flagged: " + partialNoneCorrectPct);
        System.out.println("Fact Accuracy: " + factAccuracyPct);
        System.out.println("Para Coverage: " + paraCoveragePct);
        System.out.println("Issue Count Match: " + RunCostLedger.pct(issueCountMatched, issueCountChecked, successfulCalls)
                + (issueCountMismatches.isEmpty() ? "" : " " + issueCountMismatches));
        System.out.println("Cache Hit Rate (Call 2+): " + cacheHitRatePct);
        System.out.printf("Cache Read Tokens (Calls 2+): %d%n", totalCacheReadTokensCall2Plus);
        System.out.printf("Total Benchmark Cost (all billed calls, failed included): %.4f INR%n", setCostInr.doubleValue());
        System.out.printf("Card-level accuracy (primary card only): issues expected (deduped)=%d | correct primary (status+card)=%d (%s) "
                        + "| primary card right, status wrong=%d | right card only in alternates=%d | wrong=%d | missed=%d | extra returned=%d%n",
                cardIssuesExpected, cardIssuesCorrect, RunCostLedger.pct(cardIssuesCorrect, cardIssuesExpected, successfulCalls),
                cardPrimaryRightStatusWrong, cardRightInAlternates, cardIssuesWrong, cardIssuesMissed, cardIssuesExtra);
        System.out.printf("Notices with all issues correct (status+primary card, no extra): %d of %d successful%n", noticesAllIssuesCorrect, successfulCalls);
        System.out.println("Defect issues (kind=defect): returned=" + defectReturned + " | expected="
                + (defectCardIds == null ? "n/a (no defect_cards.txt)" : String.valueOf(defectExpected)));

        if (successfulCalls == 0) {
            System.out.println("\nNo successful calls: accuracy metrics are N/A.");
        } else if (wrongMatches.isEmpty()) {
            System.out.println("\nNo wrong matches against the answer keys.");
        }
        if (!wrongMatches.isEmpty()) {
            System.out.println("\n=== WRONG MATCH DETAILS ===");
            for (WrongMatchInfo wm : wrongMatches) {
                System.out.printf("- Notice: %s | Expected: %s | Got: %s | Why: %s%n", wm.noticeId(), wm.expected(), wm.got(), wm.why());
            }
        }

        ObjectNode summaryReport = objectMapper.createObjectNode();
        summaryReport.put("set", setName);
        summaryReport.put("total_notices", totalNotices);
        summaryReport.put("successful_calls", successfulCalls);
        summaryReport.put("error_calls", errorCallsCount);
        summaryReport.put("multi_full_matches", multiFullMatchCount);
        // null = N/A (no successful calls / nothing to measure)
        summaryReport.put("wrong_full_match_rate_pct", RunCostLedger.pctValue(wrongFullMatchCount, expectedFullMatchCount, successfulCalls));
        summaryReport.put("partial_none_correct_pct", RunCostLedger.pctValue(correctPartialOrNoneCount, expectedPartialOrNoneCount, successfulCalls));
        summaryReport.put("fact_accuracy_pct", RunCostLedger.pctValue(matchedFactsCount, totalExpectedFacts, successfulCalls));
        summaryReport.put("para_coverage_pct", RunCostLedger.pctValue(coveredParasCount, totalExpectedParas, successfulCalls));
        summaryReport.put("issue_count_match_pct", RunCostLedger.pctValue(issueCountMatched, issueCountChecked, successfulCalls));
        summaryReport.put("cache_hit_rate_pct", RunCostLedger.pctValue(cacheHitCalls, successfulCalls - 1, successfulCalls));
        summaryReport.put("cache_read_tokens_calls_2_plus", totalCacheReadTokensCall2Plus);
        summaryReport.put("total_cost_inr", setCostInr.doubleValue());
        // card-level accuracy (status + the PRIMARY card per de-duplicated expected issue)
        summaryReport.put("issues_expected", cardIssuesExpected);
        summaryReport.put("issues_correct_primary", cardIssuesCorrect);
        summaryReport.put("issues_primary_right_status_wrong", cardPrimaryRightStatusWrong);
        summaryReport.put("issues_right_card_in_alternates", cardRightInAlternates);
        summaryReport.put("issues_wrong", cardIssuesWrong);
        summaryReport.put("issues_missed", cardIssuesMissed);
        summaryReport.put("issues_extra", cardIssuesExtra);
        summaryReport.put("issue_card_accuracy_pct", RunCostLedger.pctValue(cardIssuesCorrect, cardIssuesExpected, successfulCalls));
        summaryReport.put("notices_all_issues_correct", noticesAllIssuesCorrect);
        summaryReport.put("defect_issues_returned", defectReturned);
        if (defectCardIds != null) {
            summaryReport.put("defect_issues_expected", defectExpected);
        } else {
            summaryReport.putNull("defect_issues_expected");   // no defect_cards.txt: count skipped
        }
        summaryReport.set("notices", objectMapper.valueToTree(noticeEvalResults));

        spentInr[0] = totalCostInr;
        return summaryReport;
    }
}
