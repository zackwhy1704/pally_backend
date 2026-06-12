package com.pally.shared.util;

/// Server-side clamp for client-reported study durations.
///
/// Clients report how long the kid spent on a quiz/module/reading session so
/// the progress dashboard's "minutes studied this week" stat is honest.
/// Client values are NEVER trusted unbounded — a buggy or malicious client
/// could otherwise inflate the chart. We clamp to [0, 3600] seconds (one hour
/// per single activity is already a generous ceiling for a kid's study app).
public final class DurationClamp {

    /// Maximum seconds accepted for a single logged activity (1 hour).
    public static final int MAX_DURATION_SECONDS = 3600;

    private DurationClamp() {
    }

    /// Clamps a nullable client-reported duration into [0, 3600] seconds.
    /// Null (field missing / legacy client) maps to 0 for backward compatibility.
    public static int clamp(Integer seconds) {
        if (seconds == null) {
            return 0;
        }
        return Math.max(0, Math.min(MAX_DURATION_SECONDS, seconds));
    }
}
