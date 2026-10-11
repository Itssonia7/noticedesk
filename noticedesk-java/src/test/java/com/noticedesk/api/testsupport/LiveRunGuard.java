package com.noticedesk.api.testsupport;

import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.ApiUsageLogService.CallOutcome;
import com.noticedesk.api.service.llm.LlmFatalException;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Cost safeguards shared by the live runners (MatchingEvalRunnerTest, V7LivePipelineRunnerTest):
 * <ul>
 *   <li>commit check: prints HEAD; refuses to run when {@code LIVE_EXPECTED_COMMIT} is set and HEAD does not
 *       start with it (stale code costs money);</li>
 *   <li>smoke mode: {@code LIVE_SMOKE=true} runs the first notice only;</li>
 *   <li>caps: {@code LIVE_MAX_RUN_COST_INR} (default 50) per run, {@code LIVE_MAX_NOTICE_COST_INR}
 *       (default 30) per notice;</li>
 *   <li>fail fast: after {@code LIVE_MAX_CONSECUTIVE_FAILURES} (default 2) failed or unusable model calls
 *       in a row the run is aborted by throwing {@link LlmFatalException} from inside the agent, so no
 *       further call is made. Billing / auth errors are fatal on their own (see AnthropicLlmProvider).</li>
 * </ul>
 */
public final class LiveRunGuard {

    public static final BigDecimal DEFAULT_MAX_RUN_COST_INR = new BigDecimal("50");
    public static final BigDecimal DEFAULT_MAX_NOTICE_COST_INR = new BigDecimal("30");
    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 2;

    private final Function<String, String> env;
    private final int maxConsecutiveFailures;
    private int consecutiveFailures;
    private final List<String> recentFailures = new ArrayList<>();

    public LiveRunGuard(Function<String, String> env) {
        this.env = env;
        this.maxConsecutiveFailures = positiveInt(env.apply("LIVE_MAX_CONSECUTIVE_FAILURES"), DEFAULT_MAX_CONSECUTIVE_FAILURES);
    }

    /** Guard reading the process environment (system properties as a fallback, for -D on the mvn line). */
    public static LiveRunGuard fromEnvironment() {
        return new LiveRunGuard(name -> {
            String v = System.getenv(name);
            return v != null && !v.isBlank() ? v : System.getProperty(name);
        });
    }

    // ---------------------------------------------------------------- settings

    public boolean smoke() {
        return "true".equalsIgnoreCase(trim(env.apply("LIVE_SMOKE")));
    }

    public BigDecimal maxRunCostInr() {
        return positiveDecimal(env.apply("LIVE_MAX_RUN_COST_INR"), DEFAULT_MAX_RUN_COST_INR);
    }

    public BigDecimal maxNoticeCostInr() {
        return positiveDecimal(env.apply("LIVE_MAX_NOTICE_COST_INR"), DEFAULT_MAX_NOTICE_COST_INR);
    }

    public int maxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }

    /** Model for an agent: the env var when set, else the value bound from application.yml; null when neither. */
    public String model(String envVar, String fromConfig) {
        String v = trim(env.apply(envVar));
        if (v != null && !v.isEmpty()) return v;
        return fromConfig != null && !fromConfig.isBlank() ? fromConfig.trim() : null;
    }

    public void printSettings(PrintStream out) {
        out.printf(Locale.ROOT, "Live-run safeguards: run cap INR %s | notice cap INR %s | abort after %d consecutive failed calls | smoke=%s%n",
                maxRunCostInr().toPlainString(), maxNoticeCostInr().toPlainString(), maxConsecutiveFailures, smoke());
    }

    // ---------------------------------------------------------------- commit check

    /** Result of the commit check: {@code allowed} false means the runner must not make any call. */
    public record CommitCheck(boolean allowed, String head, String message) {}

    /** HEAD of the repository (full sha) or null when git is not available. */
    public static String gitHead() {
        return git("rev-parse", "HEAD");
    }

    /** Uncommitted changes in tracked files (null when git is not available). */
    public static Boolean gitDirty() {
        String s = git("status", "--porcelain", "--untracked-files=no");
        return s == null ? null : !s.isBlank();
    }

    private static String git(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compares HEAD with {@code LIVE_EXPECTED_COMMIT} (prefix match, case-insensitive). Without git the run
     * continues with a warning; with an expected commit that does not match, the run is refused.
     */
    public CommitCheck checkCommit(String head) {
        String expected = trim(env.apply("LIVE_EXPECTED_COMMIT"));
        if (head == null || head.isBlank()) {
            return new CommitCheck(true, null, "WARNING: git is not available; cannot confirm which commit is running"
                    + (expected != null && !expected.isEmpty() ? " (LIVE_EXPECTED_COMMIT=" + expected + " not checked)" : ""));
        }
        String h = head.trim().toLowerCase(Locale.ROOT);
        String shortHead = h.length() > 7 ? h.substring(0, 7) : h;
        if (expected == null || expected.isEmpty()) {
            return new CommitCheck(true, shortHead, "Running commit " + shortHead + " (LIVE_EXPECTED_COMMIT not set)");
        }
        String e = expected.toLowerCase(Locale.ROOT);
        if (e.length() < 7) {
            return new CommitCheck(false, shortHead, "REFUSED: LIVE_EXPECTED_COMMIT=" + expected
                    + " is too short; give at least 7 characters (HEAD is " + shortHead + ")");
        }
        if (!h.startsWith(e)) {
            return new CommitCheck(false, shortHead, "REFUSED: HEAD is " + shortHead + " but LIVE_EXPECTED_COMMIT=" + expected
                    + ". Run: git fetch && git reset --hard origin/<branch> && mvn clean, then retry.");
        }
        return new CommitCheck(true, shortHead, "Running commit " + shortHead + " (matches LIVE_EXPECTED_COMMIT)");
    }

    // ---------------------------------------------------------------- consecutive failures

    /** Counts call outcomes reported by the agents; aborts the run once the limit is reached. */
    public void attach(ApiUsageLogService usage) {
        usage.addOutcomeListener(this::onOutcome);
    }

    public synchronized void onOutcome(CallOutcome o) {
        if (o.usable()) {
            consecutiveFailures = 0;
            recentFailures.clear();
            return;
        }
        consecutiveFailures++;
        recentFailures.add(o.step() + "#" + o.attempt() + ":" + o.reason());
        if (consecutiveFailures >= maxConsecutiveFailures) {
            throw new LlmFatalException(consecutiveFailures + " consecutive failed or unusable model calls "
                    + recentFailures + " (LIVE_MAX_CONSECUTIVE_FAILURES=" + maxConsecutiveFailures + ")");
        }
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    // ---------------------------------------------------------------- helpers

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static int positiveInt(String s, int fallback) {
        try {
            int v = Integer.parseInt(trim(s));
            return v > 0 ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static BigDecimal positiveDecimal(String s, BigDecimal fallback) {
        try {
            BigDecimal v = new BigDecimal(trim(s));
            return v.signum() > 0 ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
