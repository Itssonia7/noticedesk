package com.noticedesk.api.agent;

/**
 * A fact a v7 writer needed for an argument but could not find in the notice or the client facts: written into the
 * draft as a {@code [[CONFIRM: <fact>]]} marker inside a conditional sentence and returned in {@code assumed_facts}
 * for the partner to confirm with the client before filing.
 *
 * @param fact     the fact to confirm, without the marker wrapper
 * @param sentence the draft sentence it supports (null when the writer gave none)
 */
public record AssumedFact(String fact, String sentence) {}
