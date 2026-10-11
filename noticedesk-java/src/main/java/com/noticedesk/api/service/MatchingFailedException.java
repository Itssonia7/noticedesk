package com.noticedesk.api.service;

/**
 * Stage 1 issue matching produced no usable result for a notice (both attempts failed, or the model
 * refused). The v7 pipeline stops for that notice instead of drafting a scaffold from a fallback issue:
 * no writer is called and no draft is produced. The message names the reason, never notice text.
 */
public class MatchingFailedException extends RuntimeException {

    public MatchingFailedException(String message) {
        super(message);
    }
}
