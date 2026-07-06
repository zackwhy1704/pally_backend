package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.util.TextSampler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Use case: check whether extracted text is relevant to an avatar's subject domain.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Build a brief wiki summary from the avatar's existing wiki pages (up to 500 chars each).</li>
 *   <li>Sample the first 500 tokens of the new content.</li>
 *   <li>Ask Claude to score relevance 0.0–1.0.</li>
 *   <li>isRelevant = score >= RELEVANCE_THRESHOLD.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CheckRelevanceUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckRelevanceUseCase.class);

    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final RelevancePort relevancePort;

    public record RelevanceResult(double score, String reason, boolean relevant, boolean studyMaterial) {}

    public RelevanceResult execute(String avatarId, String extractedText) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        String wikiSummary = buildWikiSummary(avatar, pages);
        String contentSample = TextSampler.sample(extractedText);

        log.debug("Running relevance check for avatarId={} contentLength={}", avatarId, extractedText.length());

        RelevanceScore response =
                relevancePort.check(avatar.getSubject().name(), wikiSummary, contentSample);

        // Topic-relevance only gates topically-BOUNDED subjects. GENERAL (and any future
        // unbounded subject) has no topic to be off-topic from — scoring against the literal
        // "General" false-blocks educational-but-off-"topic" content (a sales book at 0.25).
        // Bypass the topic score for unbounded subjects; the studyMaterial floor still applies
        // (it flows through unchanged for the client's isRelevant && studyMaterial gate).
        boolean relevant = avatar.getSubject().isTopicallyBounded()
                ? response.isRelevant()
                : true;
        log.info("Relevance check avatarId={} subject={} topicBounded={} score={} relevant={} studyMaterial={} reason={}",
                avatarId, avatar.getSubject(), avatar.getSubject().isTopicallyBounded(),
                response.value(), relevant, response.studyMaterial(), response.reason());

        return new RelevanceResult(response.value(), response.reason(), relevant, response.studyMaterial());
    }

    private String buildWikiSummary(Avatar avatar, List<WikiPage> pages) {
        if (pages.isEmpty()) {
            return "This is a new avatar specialising in " + avatar.getSubject().name() +
                   ". No wiki pages exist yet.";
        }
        return pages.stream()
                .limit(10)
                .map(p -> "## " + p.getTitle() + "\n" + TextSampler.sample(p.getContent(), 100))
                .collect(Collectors.joining("\n\n"));
    }
}
