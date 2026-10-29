package com.noticedesk.api.service.llm;

/**
 * A model call error that no retry can fix: billing / credit balance, authentication or permission
 * (HTTP 400 "credit balance is too low", 401, 403), or a live run aborted by its failure guard.
 * Agents never retry it and never turn it into a fallback result; it propagates to the caller.
 */
public class LlmFatalException extends LlmException {

    public LlmFatalException(String message) { super(message); }

    /**
     * The fatal error for an HTTP error status from Anthropic, or null when the error is not fatal
     * (other 4xx are request errors; 429 / 5xx are transient).
     */
    public static LlmFatalException forHttpError(int status, String body) {
        String b = body != null ? body.toLowerCase(java.util.Locale.ROOT) : "";
        if (status == 401 || b.contains("authentication_error")) {
            return new LlmFatalException("Anthropic authentication failed (HTTP " + status + "): check ANTHROPIC_API_KEY");
        }
        if (status == 403 || b.contains("permission_error")) {
            return new LlmFatalException("Anthropic refused access (HTTP " + status + "): key/workspace lacks permission");
        }
        if (b.contains("credit balance") || b.contains("billing_error") || b.contains("billing")) {
            return new LlmFatalException("Anthropic billing error (HTTP " + status + "): credit balance is too low or billing is not set up");
        }
        return null;
    }
}
