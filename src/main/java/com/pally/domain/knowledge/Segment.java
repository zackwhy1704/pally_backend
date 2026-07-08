package com.pally.domain.knowledge;

/**
 * One proposed chapter/section of an oversized upload — the unit the student picks
 * to compile. {@code pageFrom}/{@code pageTo} are 1-based inclusive (real pages for
 * PDFs; derived from char offsets for text). {@code text} is this segment's OWN
 * slice of the parent's extracted text (never the whole parent — so dedup, which
 * hashes text, can't collide siblings).
 */
public record Segment(String title, int pageFrom, int pageTo, String text) {}
