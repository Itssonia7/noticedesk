package com.noticedesk.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.agent.CitationVerificationAgent.CitationVerificationResult;
import com.noticedesk.api.agent.CitationVerificationAgent.VerifiedCitation;
import com.noticedesk.api.agent.PartialDraftingAgent.RawAiCitation;
import com.noticedesk.api.config.AppProperties;
import com.noticedesk.api.service.llm.ApiUsageLogService;
import com.noticedesk.api.service.llm.NoticeCallBudget;
import com.noticedesk.api.testsupport.RunCostLedger;
import com.noticedesk.api.testsupport.TestAppProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Citation-only live runner: runs {@link CitationVerificationAgent} (IndianKanoon only, NO model calls)
 * on a small list of citations and prints the status of each one, the number of IndianKanoon calls and
 * their configured cost. Runs only with {@code ENABLE_E2E_TESTS=true} and {@code INDIANKANOON_API_TOKEN}
 * set; otherwise it is skipped (a plain {@code mvn test} makes no network call).
 *
 * <p>Citations come from {@code CITATION_CHECK_LIST} (semicolon-separated {@code case name|court|year})
 * or default to four well-known cases plus one deliberately invented name, which must come back
 * NOT_FOUND. Per-call prices from {@code INDIANKANOON_COST_INR_PER_SEARCH} / {@code _FRAGMENT};
 * {@code IK_MAX_CALLS_PER_DRAFT} caps the calls. HTTP 401 / 403 / 429 stop the run.
 */
class CitationCheckRunnerTest {

    static final List<String> DEFAULT_LIST = List.of(
            "Hindustan Steel Ltd v. State of Orissa|Supreme Court|1969",
            "Commissioner of Income Tax v. Vatika Township Pvt Ltd|Supreme Court|2014",
            "Whirlpool Corporation v. Registrar of Trade Marks|Supreme Court|1998",
            "Mangalore Chemicals and Fertilizers Ltd v. Deputy Commissioner of Commercial Taxes|Supreme Court|1991",
            "Zorblax Fictional Industries v. Commissioner of Imaginary Taxes|Supreme Court|2003");

    @Test
    void runCitationCheckOnly() {
        if (!"true".equalsIgnoreCase(System.getenv("ENABLE_E2E_TESTS")) && !"true".equalsIgnoreCase(System.getProperty("ENABLE_E2E_TESTS"))) {
            System.out.println("Skipping CitationCheckRunnerTest (ENABLE_E2E_TESTS not set to true)");
            return;
        }
        String token = System.getenv("INDIANKANOON_API_TOKEN");
        if (token == null || token.isBlank()) {
            System.out.println("Skipping CitationCheckRunnerTest (INDIANKANOON_API_TOKEN missing)");
            return;
        }

        AppProperties props = TestAppProperties.fromApplicationYml();   // IK prices, call cap and token from env
        props.getCitation().setProvider("indiankanoon");
        props.getCitation().setIndiankanoonApiToken(token);
        List<RawAiCitation> citations = parseList(System.getenv("CITATION_CHECK_LIST"));

        ApiUsageLogService usage = new ApiUsageLogService(null, props);   // no database: prints only
        RunCostLedger ledger = RunCostLedger.attach(usage, System.out);
        CitationVerificationAgent agent = new CitationVerificationAgent(new ObjectMapper(), props, usage, null,
                new IndianKanoonClient(new ObjectMapper()));
        NoticeCallBudget budget = NoticeCallBudget.fromProperties(props, usage);

        System.out.printf(Locale.ROOT, "=== Citation check only: %d citation(s), IK call cap %d, price per search INR %s, per fragment INR %s%n",
                citations.size(), props.getCitation().getMaxCallsPerDraft(),
                usage.indianKanoonCostInr(ApiUsageLogService.IK_SEARCH).toPlainString(),
                usage.indianKanoonCostInr(ApiUsageLogService.IK_FRAGMENT).toPlainString());

        CitationVerificationResult result;
        try {
            result = agent.verifyAiCitations(citations, UUID.randomUUID(), budget);
        } catch (IndianKanoonFatalException e) {
            fail("IndianKanoon fatal error (HTTP " + e.status() + "), run stopped: " + e.getMessage());
            return;
        }

        System.out.println("--- results");
        for (VerifiedCitation vc : result.citations()) {
            System.out.printf(Locale.ROOT, "  %-16s %s (%s, %s)%s%n", vc.status(), vc.caseName(),
                    vc.court() != null ? vc.court() : "court not given", vc.year() != null ? vc.year() : "year not given",
                    vc.details() != null ? " - " + vc.details() : "");
        }
        System.out.printf(Locale.ROOT, "--- IndianKanoon calls: %s (search + fragment); flags: %s%n",
                result.summary().get("ik_calls"), result.flags());
        System.out.printf(Locale.ROOT, "--- cost: INR %s for %d logged call(s); notice budget spent INR %s%n",
                ledger.totalInr().setScale(4, RoundingMode.HALF_UP).toPlainString(), ledger.size(),
                budget.spentInr().setScale(4, RoundingMode.HALF_UP).toPlainString());

        if (System.getenv("CITATION_CHECK_LIST") == null) {
            VerifiedCitation fake = result.citations().get(result.citations().size() - 1);
            if (!CitationVerificationAgent.NOT_FOUND.equals(fake.status())) {
                fail("the invented case must be NOT_FOUND but was " + fake.status() + ": " + fake.details());
            }
        }
    }

    /** {@code case name|court|year;...}; a missing court or year is left null. */
    static List<RawAiCitation> parseList(String env) {
        List<String> entries = env != null && !env.isBlank() ? List.of(env.split(";")) : DEFAULT_LIST;
        List<RawAiCitation> out = new ArrayList<>();
        for (String entry : entries) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            String[] parts = e.split("\\|", -1);
            String name = parts[0].trim();
            String court = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : null;
            Integer year = null;
            if (parts.length > 2 && !parts[2].isBlank()) {
                try {
                    year = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ex) {
                    System.out.println("WARNING: year not numeric in '" + e + "'; left unset");
                }
            }
            if (!name.isEmpty()) out.add(new RawAiCitation(name, court, year, null, "citation check"));
        }
        return out;
    }

    @Test
    void listParsing_isOffline() {
        List<RawAiCitation> list = parseList("A v. B|Supreme Court|1999; C v. D||; E v. F|High Court of Delhi|x");
        if (list.size() != 3 || list.get(0).year() != 1999 || list.get(1).court() != null || list.get(2).year() != null) {
            fail("unexpected parse: " + list);
        }
        if (parseList(null).size() != DEFAULT_LIST.size()) fail("default list expected");
        BigDecimal zero = new ApiUsageLogService(null, new AppProperties()).indianKanoonCostInr(ApiUsageLogService.IK_FRAGMENT);
        if (zero.signum() != 0) fail("default IndianKanoon price must be 0");
    }
}
