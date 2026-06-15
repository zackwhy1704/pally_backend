package com.pally.infrastructure.ai;

import com.pally.domain.curriculum.CurriculumService.ParsedTopic;
import com.pally.domain.curriculum.port.SyllabusParserPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Infrastructure adapter implementing {@link SyllabusParserPort} by
 * delegating to {@link ClaudeSyllabusParser}.
 */
@Component
@RequiredArgsConstructor
public class SyllabusParserAdapter implements SyllabusParserPort {

    private final ClaudeSyllabusParser parser;

    @Override
    public List<ParsedTopic> parse(String subject, String rawText) {
        return parser.parse(subject, rawText).stream()
                .map(p -> new ParsedTopic(p.name(), p.slug()))
                .toList();
    }
}
