package com.noticedesk.api.agent;

import com.noticedesk.api.service.llm.LlmFatalException;

/**
 * An IndianKanoon response that no retry can fix and that must stop the run: HTTP 401 / 403 (token
 * rejected or plan does not allow the call) or 429 (rate limit). A subclass of {@link LlmFatalException}
 * so the pipeline, the workflow and the live runners stop exactly as they do for a billing or auth error
 * of the model provider, instead of silently marking every citation NOT_CHECKED.
 */
public class IndianKanoonFatalException extends LlmFatalException {

    private final int status;

    public IndianKanoonFatalException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** The fatal error for {@code status}, or null when the status is not fatal (other errors are per-citation). */
    public static IndianKanoonFatalException forHttpStatus(int status) {
        return switch (status) {
            case 401 -> new IndianKanoonFatalException(status, "IndianKanoon rejected the token (HTTP 401): check INDIANKANOON_API_TOKEN");
            case 403 -> new IndianKanoonFatalException(status, "IndianKanoon refused the call (HTTP 403): token or plan lacks permission");
            case 429 -> new IndianKanoonFatalException(status, "IndianKanoon rate limit hit (HTTP 429): run stopped, retry later");
            default -> null;
        };
    }
}
