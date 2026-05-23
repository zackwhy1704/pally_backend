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

    public record RelevanceResult(double score, String reason, boolean relevant) {}

    public RelevanceResult execute(String avatarId, String extractedText) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        String wikiSummary = buildWikiSummary(avatar, pages);
        String contentSample = TextSampler.sample(extractedText);

        log.debug("Running relevance check for avatarId={} contentLength={}", avatarId, extractedText.length());

        RelevanceScore response =
                relevancePort.check(avatar.getSubject().name(), wikiSummary, contentSample);

        boolean relevant = response.isRelevant();
        log.info("Relevance check avatarId={} score={} relevant={} reason={}",
                avatarId, response.value(), relevant, response.reason());

        return new RelevanceResult(response.value(), response.reason(), relevant);
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
