package com.pally.domain.chat;

import com.pally.domain.knowledge.WikiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates Socratic hint trees from wiki pages at compile time.
 * Runs during wiki compilation — no LLM needed for keyword extraction.
 */
@Component
public class HintTreeGenerator {

    private static final Logger log = LoggerFactory.getLogger(HintTreeGenerator.class);

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been",
            "have", "has", "had", "do", "does", "did", "and", "or", "but",
            "in", "on", "at", "to", "for", "of", "with", "this", "that",
            "it", "its", "as", "by", "from", "can", "will", "also", "when",
            "which", "who", "what", "how", "their", "they", "we", "you"
    );

    private final HintTreeRepository hintTreeRepository;

    public HintTreeGenerator(HintTreeRepository hintTreeRepository) {
        this.hintTreeRepository = hintTreeRepository;
    }

    /**
     * Generates and persists a hint tree for a single wiki page.
     * Called after each page is created/updated during wiki compilation.
     */
    public void generateForPage(String avatarId, WikiPage page) {
        try {
            List<String> keywords = extractKeywords(page.getTitle() + " " + page.getContent(), 15);
            List<SocraticHintTree.HintStep> hints = buildHints(page.getTitle(), page.getContent());

            SocraticHintTree tree = SocraticHintTree.create(
                    avatarId, page.getSlug(), keywords, hints);
            hintTreeRepository.save(tree);

            log.debug("[Socratic] Generated hint tree for slug={} keywords={} steps={}",
                    page.getSlug(), keywords.size(), hints.size());
        } catch (Exception e) {
            log.warn("[Socratic] Failed to generate hint tree for slug={}: {}",
                    page.getSlug(), e.getMessage());
        }
    }

    private List<String> extractKeywords(String text, int maxKeywords) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() > 3 && !STOP_WORDS.contains(w))
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(maxKeywords)
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private List<SocraticHintTree.HintStep> buildHints(String title, String content) {
        // Extract key concepts from content for hint generation
        String[] sentences = content.split("[.!?]+");
        List<SocraticHintTree.HintStep> hints = new ArrayList<>();

        // Step 1: Recall / activate prior knowledge
        hints.add(new SocraticHintTree.HintStep(
                1,
                "What do you already know about " + simplify(title) + "?",
                extractFirstKeyword(content)
        ));

        // Step 2: Point to the relevant concept
        if (sentences.length > 0) {
            String concept = extractConcept(sentences[0]);
            hints.add(new SocraticHintTree.HintStep(
                    2,
                    "Can you think of an example that shows " + concept + "?",
                    extractFirstKeyword(sentences[0])
            ));
        }

        // Step 3: Apply the concept
        hints.add(new SocraticHintTree.HintStep(
                3,
                "Looking at your notes about " + simplify(title) + ", what does that tell you?",
                extractFirstKeyword(title)
        ));

        return hints;
    }

    private String simplify(String text) {
        return text.replaceAll("[_-]", " ").toLowerCase();
    }

    private String extractConcept(String sentence) {
        if (sentence.length() > 60) {
            return "this idea";
        }
        return sentence.trim().toLowerCase();
    }

    private String extractFirstKeyword(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() > 3 && !STOP_WORDS.contains(w))
                .findFirst()
                .orElse("this");
    }
}
