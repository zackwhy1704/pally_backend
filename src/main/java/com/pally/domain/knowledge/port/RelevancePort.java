package com.pally.domain.knowledge.port;

import com.pally.domain.knowledge.RelevanceScore;

/**
 * Port for checking whether extracted text content is relevant to an avatar's subject domain.
 */
public interface RelevancePort {

    /**
     * Evaluates relevance of {@code contentSample} given the avatar's subject and existing wiki context.
     *
     * @param subject      the avatar's subject name (e.g. "MATH", "SCIENCE")
     * @param wikiSummary  a brief summary of existing wiki pages (may be empty)
     * @param contentSample a sample of the text to evaluate
     * @return a {@link RelevanceScore} with score 0.0–1.0 and a short reason
     */
    RelevanceScore check(String subject, String wikiSummary, String contentSample);
}
