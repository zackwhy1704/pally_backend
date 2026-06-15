package com.pally.domain.curriculum.port;

import com.pally.domain.curriculum.CurriculumService.ParsedTopic;

import java.util.List;

/**
 * Domain port for parsing a raw syllabus text into an ordered topic list.
 * Implemented in {@code infrastructure/ai}; never import JPA or HTTP here.
 */
public interface SyllabusParserPort {

    /**
     * Parses a raw syllabus string into an ordered list of topic name + slug pairs.
     *
     * @param subject  e.g. "MATH"
     * @param rawText  syllabus text (possibly long)
     * @return ordered list; empty if nothing was detected
     */
    List<ParsedTopic> parse(String subject, String rawText);
}
