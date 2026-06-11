package com.pally.domain.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Evaluates the quality of OCR-extracted text from image uploads.
 * Rejects garbled/empty/unreadable content before it reaches the wiki compiler,
 * preventing garbage-in-garbage-out wiki pages.
 *
 * <p>Quality levels:
 * <ul>
 *   <li>GOOD — clean text, proceed normally</li>
 *   <li>BORDERLINE — text is usable but may have issues; let user review</li>
 *   <li>REJECTED — text is too garbled/short to be useful</li>
 * </ul>
 */
@Component
public class OcrQualityGate {

    private static final Logger log = LoggerFactory.getLogger(OcrQualityGate.class);

    public enum Quality { GOOD, BORDERLINE, REJECTED }

    public record QualityResult(Quality quality, String reason, String cleanedText) {}

    /**
     * Evaluate OCR output quality.
     *
     * @param extractedText the raw text from OCR
     * @param fileSizeBytes original image file size in bytes
     * @return quality verdict with reason and cleaned text
     */
    public QualityResult evaluate(String extractedText, long fileSizeBytes) {
        // 1. Empty/blank
        if (extractedText == null || extractedText.isBlank()) {
            log.info("[OcrQuality] REJECTED: empty/blank text, fileSize={}B", fileSizeBytes);
            return new QualityResult(Quality.REJECTED, "Couldn't read any text from this image.", "");
        }

        String trimmed = extractedText.strip();
        String[] words = trimmed.split("\\s+");
        int wordCount = words.length;

        // 2. Too few words
        if (wordCount < 30) {
            log.info("[OcrQuality] REJECTED: only {} words detected, fileSize={}B", wordCount, fileSizeBytes);
            return new QualityResult(Quality.REJECTED,
                    "Only " + wordCount + " words detected — the image may be too blurry or not contain enough text.",
                    trimmed);
        }

        // 3. Large image but very few words — likely blurry/non-text
        if (fileSizeBytes > 50_000 && wordCount < 20) {
            log.info("[OcrQuality] REJECTED: large image ({}B) but only {} words", fileSizeBytes, wordCount);
            return new QualityResult(Quality.REJECTED,
                    "Image is large but very little text was detected — it may be too blurry or not a text image.",
                    trimmed);
        }

        // 4. Garble ratio: proportion of alphanumeric + whitespace chars
        long cleanChars = trimmed.chars()
                .filter(c -> Character.isLetterOrDigit(c) || Character.isWhitespace(c))
                .count();
        double cleanRatio = (double) cleanChars / trimmed.length();

        if (cleanRatio < 0.55) {
            log.info("[OcrQuality] REJECTED: garble ratio={} (below 0.55), fileSize={}B",
                    String.format("%.2f", cleanRatio), fileSizeBytes);
            return new QualityResult(Quality.REJECTED,
                    "The text came through garbled — try retaking the photo with better lighting and angle.",
                    trimmed);
        }

        // 5. Real-word ratio: 3+ char alphabetic-only words as fraction of total
        long realWords = 0;
        for (String word : words) {
            if (word.length() >= 3 && word.chars().allMatch(Character::isLetter)) {
                realWords++;
            }
        }
        double wordRatio = (double) realWords / wordCount;

        if (wordRatio < 0.35) {
            log.info("[OcrQuality] BORDERLINE: word ratio={} (below 0.35), fileSize={}B",
                    String.format("%.2f", wordRatio), fileSizeBytes);
            return new QualityResult(Quality.BORDERLINE,
                    "This looks like it's mostly numbers or symbols — check that the important text came through.",
                    trimmed);
        }

        // 6. Almost clean but not pristine
        if (cleanRatio < 0.70 || wordRatio < 0.50) {
            log.info("[OcrQuality] BORDERLINE: cleanRatio={} wordRatio={}, fileSize={}B",
                    String.format("%.2f", cleanRatio), String.format("%.2f", wordRatio), fileSizeBytes);
            return new QualityResult(Quality.BORDERLINE,
                    "Some parts of the text might not be perfect — review the extracted text below.",
                    trimmed);
        }

        // 7. All checks pass
        log.info("[OcrQuality] GOOD: {} words, cleanRatio={}, wordRatio={}, fileSize={}B",
                wordCount, String.format("%.2f", cleanRatio), String.format("%.2f", wordRatio), fileSizeBytes);
        return new QualityResult(Quality.GOOD, null, trimmed);
    }
}
