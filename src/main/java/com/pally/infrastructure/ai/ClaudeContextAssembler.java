package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.chat.AssembledContext;
import com.pally.domain.knowledge.DetectedTopic;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiPageIndex;
import com.pally.domain.knowledge.WikiRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds a tiered system prompt for Claude using only the wiki context
 * that is relevant to the user's current message.
 *
 * <p>Tiers:
 * <ul>
 *   <li>Tier 1 (~200 tokens): Avatar identity — name, subject, grade, pedagogy</li>
 *   <li>Tier 2 (~500 tokens): Wiki index — all active page titles and slugs</li>
 *   <li>Tier 3 (~3000 tokens): Full content of up to 5 topic-matched pages</li>
 *   <li>Tier 4 (~1000 tokens): Prerequisite pages for Tier-3 entries</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ClaudeContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ClaudeContextAssembler.class);
    private static final int MAX_TIER3_PAGES = 5;
    private static final int MAX_TIER4_PAGES = 3;

    private final TopicRouter topicRouter;
    private final WikiRepository wikiRepository;
    private final ObjectMapper objectMapper;

    public AssembledContext assemble(Avatar avatar, String userMessage) {
        long assembleStart = System.currentTimeMillis();

        // Tier 2: index (needed by router and included in prompt)
        List<WikiPageIndex> index = wikiRepository.getIndex(avatar.getId());

        // Topic routing
        long routerStart = System.currentTimeMillis();
        List<DetectedTopic> topics = topicRouter.route(userMessage, avatar.getSubject().name(), index);
        long routerMs = System.currentTimeMillis() - routerStart;

        // Tier 3: relevant pages
        List<String> keywords = topics.stream().map(DetectedTopic::slugKeyword).toList();
        List<WikiPage> tier3Pages = wikiRepository.findByKeywords(avatar.getId(), keywords, MAX_TIER3_PAGES);

        // Tier 4: prerequisites not already in Tier 3
        List<WikiPage> tier4Pages = loadPrerequisites(avatar.getId(), tier3Pages);

        // Record retrieval for all pages we loaded
        List<String> allSlugs = Stream.concat(tier3Pages.stream(), tier4Pages.stream())
                .map(WikiPage::getSlug).distinct().toList();
        if (!allSlugs.isEmpty()) {
            wikiRepository.recordRetrieval(avatar.getId(), allSlugs);
        }

        // Build system prompt
        String tier1 = buildTier1(avatar);
        String tier2 = buildTier2(index);
        String tier3 = buildTier3(tier3Pages);
        String tier4 = buildTier4(tier4Pages);
        String systemPrompt = assembleFinalPrompt(tier1, tier2, tier3, tier4);

        long assemblyMs = System.currentTimeMillis() - assembleStart;

        // Build harness trace
        String harnessTrace = buildHarnessTrace(
                tier1, tier2, tier3Pages, tier3, tier4Pages, tier4,
                routerMs, assemblyMs);

        log.info("[Harness] Assembled context for avatarId={} tier3Pages={} tier4Pages={} routerMs={} assemblyMs={}",
                avatar.getId(), tier3Pages.size(), tier4Pages.size(), routerMs, assemblyMs);

        return new AssembledContext(systemPrompt, harnessTrace);
    }

    private String buildTier1(Avatar avatar) {
        String gradeCtx = avatar.getGradeLevel() != null
                ? "Grade: " + avatar.getGradeLevel() + ". " : "";
        String curriculumCtx = avatar.getCurriculumType() != null
                ? "Curriculum: " + avatar.getCurriculumType() + ". " : "";
        String pedagogyStyle = switch (avatar.getPedagogyMode()) {
            case SOCRATIC -> "Use the Socratic method — guide with questions before explaining.";
            case DIRECT -> "Teach directly with clear step-by-step explanations.";
        };

        return """
                You are %s, a friendly AI tutor specialising in %s for children aged 8–14.
                %s%s%s
                Always be encouraging, patient, and age-appropriate.
                Use simple language and examples kids love: food, games, sports, animals.
                ONLY answer questions about %s. Kindly redirect off-topic questions.
                When you reference the knowledge base, end your reply with: SOURCE: [page-slug]
                """.formatted(
                avatar.getName(), avatar.getSubject().name(),
                gradeCtx, curriculumCtx, pedagogyStyle,
                avatar.getSubject().name());
    }

    private String buildTier2(List<WikiPageIndex> index) {
        if (index.isEmpty()) return "## Knowledge Index\nNo pages yet.\n";
        String entries = index.stream()
                .map(e -> "- [%s] %s — %s".formatted(e.slug(), e.title(), e.summary()))
                .collect(Collectors.joining("\n"));
        return "## Knowledge Index\n" + entries + "\n";
    }

    private String buildTier3(List<WikiPage> pages) {
        if (pages.isEmpty()) return "";
        String content = pages.stream()
                .map(p -> "### " + p.getTitle() + " [" + p.getSlug() + "]\n" + p.getContent())
                .collect(Collectors.joining("\n\n"));
        return "## Relevant Pages\n" + content + "\n";
    }

    private String buildTier4(List<WikiPage> pages) {
        if (pages.isEmpty()) return "";
        String content = pages.stream()
                .map(p -> "### " + p.getTitle() + " [" + p.getSlug() + "] (prerequisite)\n" + p.getContent())
                .collect(Collectors.joining("\n\n"));
        return "## Background Knowledge\n" + content + "\n";
    }

    private String assembleFinalPrompt(String tier1, String tier2, String tier3, String tier4) {
        StringBuilder sb = new StringBuilder();
        sb.append(tier1).append("\n");
        sb.append(tier2).append("\n");
        if (!tier3.isBlank()) sb.append(tier3).append("\n");
        if (!tier4.isBlank()) sb.append(tier4).append("\n");
        return sb.toString();
    }

    private List<WikiPage> loadPrerequisites(String avatarId, List<WikiPage> tier3Pages) {
        List<String> tier3Slugs = tier3Pages.stream().map(WikiPage::getSlug).toList();
        List<WikiPage> prereqs = new ArrayList<>();
        for (WikiPage page : tier3Pages) {
            List<WikiPage> pagePrereqs = wikiRepository.findPrerequisitesOf(avatarId, page.getSlug());
            for (WikiPage prereq : pagePrereqs) {
                if (!tier3Slugs.contains(prereq.getSlug()) &&
                        prereqs.stream().noneMatch(p -> p.getSlug().equals(prereq.getSlug()))) {
                    prereqs.add(prereq);
                    if (prereqs.size() >= MAX_TIER4_PAGES) return prereqs;
                }
            }
        }
        return prereqs;
    }

    private String buildHarnessTrace(
            String tier1, String tier2,
            List<WikiPage> tier3Pages, String tier3,
            List<WikiPage> tier4Pages, String tier4,
            long routerMs, long assemblyMs
    ) {
        try {
            ObjectNode trace = objectMapper.createObjectNode();
            trace.put("tier1_tokens", estimateTokens(tier1));
            trace.put("tier2_tokens", estimateTokens(tier2));
            trace.set("tier3_pages", objectMapper.valueToTree(tier3Pages.stream().map(WikiPage::getSlug).toList()));
            trace.put("tier3_tokens", estimateTokens(tier3));
            trace.set("tier4_pages", objectMapper.valueToTree(tier4Pages.stream().map(WikiPage::getSlug).toList()));
            trace.put("tier4_tokens", estimateTokens(tier4));
            trace.put("total_tokens", estimateTokens(tier1) + estimateTokens(tier2) + estimateTokens(tier3) + estimateTokens(tier4));
            trace.put("topic_router_ms", routerMs);
            trace.put("assembly_ms", assemblyMs);
            return objectMapper.writeValueAsString(trace);
        } catch (Exception e) {
            log.warn("[Harness] Failed to serialize trace: {}", e.getMessage());
            return "{}";
        }
    }

    private int estimateTokens(String text) {
        return text == null ? 0 : text.length() / 4;
    }
}
