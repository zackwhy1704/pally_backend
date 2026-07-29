package com.pally.shared.util;

/**
 * Strips characters that a Postgres {@code text} column cannot store or that are
 * junk from a PDF/OCR extractor, while preserving legitimate whitespace.
 *
 * <p>The immediate cause: PDFBox extraction of some PDFs (notably CJK documents
 * with subset fonts) yields a NUL byte {@code 0x00}. Postgres rejects it with
 * SQLState 22021 ("invalid byte sequence for encoding UTF8"), which surfaced to
 * users as a generic 400 "couldn't be saved" on upload. Applied at the
 * extraction boundary so no extracted document text can dead-end an upload.
 *
 * <p>Preserves tab / newline / carriage-return (real structure); drops NUL and
 * every other C0/C1 control character. Null-safe and idempotent; allocates only
 * when something is actually removed.
 */
public final class TextSanitizer {

    private TextSanitizer() {}

    public static String stripUnstorableChars(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // NUL (0x00) is an ISO control char, so this catches the 22021 culprit
            // and every other C0/C1 control while KEEPING tab / newline / CR.
            boolean drop = Character.isISOControl(c) && c != '\t' && c != '\n' && c != '\r';
            if (drop) {
                if (out == null) out = new StringBuilder(s.length()).append(s, 0, i);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }
}
