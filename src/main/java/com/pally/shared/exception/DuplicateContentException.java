package com.pally.shared.exception;

/**
 * Thrown when an upload is an exact or near-duplicate of content already
 * in the avatar's knowledge base. Maps to HTTP 409 with a structured body
 * so the Flutter client can show a helpful message without surfacing a raw
 * error (same pattern as UpgradeRequiredException → paywall sheet).
 *
 * <p>Two kinds:
 * <ul>
 *   <li>EXACT — SHA-256 hash of normalised text matches an existing file.</li>
 *   <li>SIMILAR — Jaccard token similarity ≥ threshold against existing files.</li>
 * </ul>
 */
public class DuplicateContentException extends PallyException {

    public enum Kind { EXACT, SIMILAR }

    private final Kind kind;
    private final String existingFileName;
    private final double similarity; // 1.0 for EXACT

    public DuplicateContentException(Kind kind, String existingFileName, double similarity) {
        super(buildMessage(kind, existingFileName), 409);
        this.kind = kind;
        this.existingFileName = existingFileName;
        this.similarity = similarity;
    }

    public Kind getKind()                  { return kind; }
    public String getExistingFileName()    { return existingFileName; }
    public double getSimilarity()          { return similarity; }

    private static String buildMessage(Kind kind, String name) {
        return kind == Kind.EXACT
                ? "This file is identical to \"" + name + "\" already in your Mochi's brain."
                : "This file is very similar to \"" + name + "\" already in your Mochi's brain.";
    }
}
