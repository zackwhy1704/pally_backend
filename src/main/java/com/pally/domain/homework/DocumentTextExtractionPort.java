package com.pally.domain.homework;

/**
 * Port that turns a homework file's bytes into plain text, routing by MIME type
 * (image → OCR, PDF → text extraction, plain text → decode). Best-effort:
 * returns "" rather than throwing when the file can't be read, because the
 * artifact is still viewable and the teacher can mark it by hand.
 */
public interface DocumentTextExtractionPort {

    /** @return extracted text, or "" if the file is unreadable/unsupported. Never null. */
    String extract(byte[] bytes, String mimeType);
}
