package com.pally.domain.knowledge.groundedness;

/**
 * A single checkable assertion pulled from generated content.
 *
 * @param text     the claim sentence
 * @param hardFact true if it asserts a hard fact (number / formula / date /
 *                 multi-word named entity). Drives the fabrication-vs-elaboration
 *                 decision: only NOT_IN_SOURCE hard facts are FLAGGED; soft
 *                 elaboration is ALLOWED.
 */
public record Claim(String text, boolean hardFact) {}
