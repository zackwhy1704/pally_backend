package com.pally.shared.util;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Single source of truth for Apalchi's operating timezone.
 *
 * <p>Singapore (SGT, UTC+8) is the jurisdiction for all user-facing day
 * boundaries: streaks, daily caps, weekly reports, and activity logs.
 * Storage timestamps remain UTC {@code Instant}s — only the "which calendar
 * day does this instant belong to?" conversion uses SGT.
 *
 * <p>Usage:
 * <pre>{@code
 *   LocalDate today = LocalDate.now(PallyTime.SGT);
 *   Instant dayStart = today.atStartOfDay(PallyTime.SGT).toInstant();
 * }</pre>
 */
public final class PallyTime {

    private PallyTime() {}

    public static final ZoneId SGT = ZoneId.of("Asia/Singapore");

    /** Returns today's date in Singapore time. */
    public static LocalDate todaySgt() {
        return LocalDate.now(SGT);
    }
}
