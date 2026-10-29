package com.noticedesk.api.service.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * What this process knows about the provider's prompt cache: per (model, prefix hash), when the prefix
 * was last written to or read from the cache and how many tokens the API counted for it. Used by
 * {@link NoticeCallBudget} to price a cacheable prefix (system prompt + catalogue, or the notice prefix of
 * the writers) at the cache-read rate when it is still warm, and to replace the character-based token
 * estimate of the prefix by the measured count once the API has reported one.
 *
 * <p>Nothing here is a guarantee: the cache lives at the provider. The registry is only used to make the
 * worst-case estimate of the next call less pessimistic once the same prefix was sent recently, so the
 * warm window is shorter than the provider's 5-minute ephemeral TTL to leave a margin for clock and
 * transit time. A prefix the API never reported cache tokens for (too short to cache, stub provider) is
 * never warm and keeps the cache-write price.
 */
public final class PromptCacheRegistry {

    /** The provider's ephemeral cache TTL (refreshed on every use). */
    public static final Duration CACHE_TTL = Duration.ofMinutes(5);
    /** Margin subtracted from the TTL before a prefix counts as warm. */
    public static final Duration WARM_MARGIN = Duration.ofSeconds(30);

    /** @param measuredTokens tokens the API counted for the prefix (cache write or read), or null when unknown */
    public record Entry(Integer measuredTokens, Instant lastUse) {}

    private final Clock clock;
    private final Map<String, Entry> entries = new HashMap<>();

    public PromptCacheRegistry() {
        this(Clock.systemUTC());
    }

    public PromptCacheRegistry(Clock clock) {
        this.clock = clock;
    }

    /** SHA-256 (hex) of a prefix text; the empty/null prefix hashes to the empty string. */
    public static String hash(String prefixText) {
        if (prefixText == null || prefixText.isEmpty()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(prefixText.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }

    /**
     * Records that the API just wrote or read {@code prefixHash} for {@code model}: the prefix is warm from
     * now, and {@code measuredTokens} (cache_creation + cache_read tokens of the response) is remembered
     * when positive.
     */
    public synchronized void recordUse(String model, String prefixHash, Integer measuredTokens) {
        if (prefixHash == null || prefixHash.isEmpty()) return;
        String key = key(model, prefixHash);
        Entry old = entries.get(key);
        Integer tokens = measuredTokens != null && measuredTokens > 0 ? measuredTokens
                : old != null ? old.measuredTokens() : null;
        entries.put(key, new Entry(tokens, clock.instant()));
    }

    /** Remembers a measured token count for a prefix without marking it warm (e.g. from an earlier run). */
    public synchronized void recordMeasuredTokens(String model, String prefixHash, int measuredTokens) {
        if (prefixHash == null || prefixHash.isEmpty() || measuredTokens <= 0) return;
        String key = key(model, prefixHash);
        Entry old = entries.get(key);
        entries.put(key, new Entry(measuredTokens, old != null ? old.lastUse() : null));
    }

    /** True when the prefix was used within the warm window (TTL minus margin). */
    public synchronized boolean isWarm(String model, String prefixHash) {
        Entry e = entries.get(key(model, prefixHash));
        if (e == null || e.lastUse() == null) return false;
        Duration age = Duration.between(e.lastUse(), clock.instant());
        return !age.isNegative() && age.compareTo(CACHE_TTL.minus(WARM_MARGIN)) <= 0;
    }

    /** The token count the API reported for the prefix, or null when no call has reported one yet. */
    public synchronized Integer measuredTokens(String model, String prefixHash) {
        Entry e = entries.get(key(model, prefixHash));
        return e != null ? e.measuredTokens() : null;
    }

    public synchronized void clear() {
        entries.clear();
    }

    private static String key(String model, String prefixHash) {
        return (model != null ? model.trim() : "") + "|" + prefixHash;
    }
}
