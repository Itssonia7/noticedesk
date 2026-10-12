package com.noticedesk.api.service;

/**
 * Stage 1 issue matching produced no usable result for a notice (both attempts failed, or the model
 * refused). The v7 pipeline stops for that notice instead of drafting a scaffold from a fallback issue:
 * no writer is called and no draft is produced. The message names the reason, never notice text.
 */
public class MatchingFailedException extends RuntimeException {

    private final boolean costCap;

    public MatchingFailedException(String message) {
        this(message, false);
    }

    /** {@code costCap}: the matching call was refused by the per-notice / run cost cap (no call made). */
    public MatchingFailedException(String message, boolean costCap) {
        super(message);
        this.costCap = costCap;
    }

    public boolean costCap() {
        return costCap;
    }
}
