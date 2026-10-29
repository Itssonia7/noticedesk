package com.noticedesk.api.service.learned;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noticedesk.api.config.AppProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.function.Function;

/**
 * Command-line tool for the learned library ({@code $PRIVATE_SEED_DIR/learned}). Opt-in: runs only with
 * {@code LEARNED_TOOL=true} and {@code PRIVATE_SEED_DIR} set, e.g.
 * <pre>
 * LEARNED_TOOL=true PRIVATE_SEED_DIR=~/noticedesk-private mvn -o test -Dtest=LearnedLibraryTool
 * LEARNED_TOOL=true PRIVATE_SEED_DIR=... LEARNED_APPROVE=LRN-1a2b3c4d LEARNED_APPROVED_BY="CA Name" mvn -o test -Dtest=LearnedLibraryTool
 * </pre>
 * Lists every entry (id, status, approver, notice id, issue, document type, stage, ground shortened to 80 characters);
 * with {@code LEARNED_APPROVE} it marks that entry approved by {@code LEARNED_APPROVED_BY} and writes the file back.
 * Approved entries reach the matcher only with {@code CATALOGUE_INCLUDE_LEARNED=true}.
 */
class LearnedLibraryTool {

    @Test
    void learnedLibraryTool() {
        Function<String, String> env = name -> {
            String v = System.getenv(name);
            return v != null && !v.isBlank() ? v : System.getProperty(name);
        };
        Assumptions.assumeTrue("true".equalsIgnoreCase(env.apply("LEARNED_TOOL")), "LEARNED_TOOL not set to true");
        String dir = env.apply("PRIVATE_SEED_DIR");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), "PRIVATE_SEED_DIR not set");
        AppProperties props = new AppProperties();
        props.setPrivateSeedDir(dir);
        run(new LearnedLibrary(props, new ObjectMapper()), env, System.out);
    }

    /** Loads the library, approves {@code LEARNED_APPROVE} when set, then lists every entry. */
    static void run(LearnedLibrary library, Function<String, String> env, PrintStream out) {
        out.println(library.load().line());
        String approve = env.apply("LEARNED_APPROVE");
        if (approve != null && !approve.isBlank()) {
            String by = env.apply("LEARNED_APPROVED_BY");
            if (by == null || by.isBlank()) throw new IllegalArgumentException("set LEARNED_APPROVED_BY to approve " + approve.trim());
            LearnedEntry e = library.approve(approve.trim(), by);
            out.println("approved " + e.entryId() + " by " + e.approvedBy() + " at " + e.approvedAt());
        }
        for (LearnedEntry e : library.entries()) {
            String ground = e.ground() == null ? "" : e.ground().replaceAll("\\s+", " ");
            if (ground.length() > 80) ground = ground.substring(0, 77) + "...";
            out.printf("%s | %s%s | notice %s issue #%d | %s | stage %s | %s%n", e.entryId(), e.status(),
                    e.approvedBy() != null ? " by " + e.approvedBy() : "", e.noticeId(), e.issueNo(), e.documentType(),
                    e.stage(), ground);
        }
    }
}
