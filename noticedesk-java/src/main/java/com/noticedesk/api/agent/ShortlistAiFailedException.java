package com.noticedesk.api.agent;

import com.noticedesk.api.service.MatchingFailedException;

/**
 * The shortlist's AI title pass failed or returned nothing usable while {@code matching.shortlist.ai-required} (env
 * {@code SHORTLIST_AI_REQUIRED}, default true) is on: the notice stops BEFORE the matching call, so a labels-only
 * shortlist never reaches the paid matcher and the writers by accident (live run of 13-11-2026: a title pass killed by
 * an HTTP 400 fell through to a 17-card shortlist, "none" on 8 of 9 grounds and INR 83 of writer calls). The live
 * runners print {@link #consoleLine()} and the flag {@link #FLAG}, mark the notice stopped and go on to the next one.
 * With the switch off the old behaviour stays: labels only and {@code warn:shortlist_ai_skipped}.
 *
 * <p>The message names the reason (an exception class, a stop reason, a budget refusal), never notice text.
 */
public class ShortlistAiFailedException extends MatchingFailedException {

    /** Draft flag (block): the notice was stopped before matching because the required title pass failed. */
    public static final String FLAG = "block:shortlist_ai_failed";
    /** Environment variable that turns the requirement off. */
    public static final String ENV = "SHORTLIST_AI_REQUIRED";

    private final String reason;

    public ShortlistAiFailedException(String reason) {
        super(consoleLine(reason), false);
        this.reason = reason != null ? reason : "unknown";
    }

    /** Why the title pass could not be used (one line, no content). */
    public String reason() {
        return reason;
    }

    /** The line the runner prints for the stopped notice. */
    public String consoleLine() {
        return consoleLine(reason);
    }

    static String consoleLine(String reason) {
        return "STOPPED: shortlist title pass failed (" + (reason != null ? reason : "unknown") + "); set " + ENV
                + "=off to match by labels only";
    }
}
