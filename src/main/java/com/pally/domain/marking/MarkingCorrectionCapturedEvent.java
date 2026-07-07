package com.pally.domain.marking;

/**
 * Published after a substantive teacher marking correction is captured. Decouples
 * the WRITE (capture, at release) from the FEED (debounced recompile) — the
 * compiler reacts to this asynchronously, so capture never waits on a compile.
 */
public record MarkingCorrectionCapturedEvent(String classId) {}
