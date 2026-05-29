package com.pally.domain.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a built-in starter wiki pack for a freshly-created avatar so the
 * tutor is immediately useful before the child uploads anything. Without
 * this, a brand-new tutor refuses every question with "no notes yet" —
 * the #1 activation killer in the audit.
 *
 * <p>Packs live in {@code src/main/resources/seed/{SUBJECT}.json}. Pages
 * are written with {@code is_seed = true} so the wiki compiler can prefer
 * the child's real uploads when both exist for the same slug, and the UI
 * can badge them if it ever wants to.
 */
@Service
@RequiredArgsConstructor
public class SeedContentService {

    private static final Logger log =
            LoggerFactory.getLogger(SeedContentService.class);

    private final WikiPageJpaRepository wikiJpa;
    private final ObjectMapper mapper;

    /// Idempotent: returns 0 when no pack exists for the subject or the
    /// avatar already has seed rows (re-seeding would dup).
    @Transactional
    public int seedForAvatar(String avatarId, String subject) {
        if (subject == null || subject.isBlank()) return 0;
        // Don't re-seed: any rows on the avatar already mean we ran before
        // OR the user has uploaded real content. Either way, leave it alone.
        if (wikiJpa.countByAvatarId(avatarId) > 0) return 0;

        List<SeedPage> pages = loadPack(subject);
        if (pages.isEmpty()) {
            log.info("[Seed] no pack for subject={} — skipping", subject);
            return 0;
        }
        Instant now = Instant.now();
        List<WikiPageJpaEntity> rows = new ArrayList<>(pages.size());
        for (SeedPage p : pages) {
            WikiPageJpaEntity e = new WikiPageJpaEntity();
            e.setId(IdGenerator.newId());
            e.setAvatarId(avatarId);
            e.setSlug(p.slug);
            e.setTitle(p.title);
            e.setContent(p.content);
            e.setCertainty(WikiPage.Certainty.INFERRED);
            e.setStatus(WikiPage.Status.ACTIVE);
            e.setUpdatedAt(now);
            e.setQualityScore(0);
            e.setHumanVerified(false);
            e.setQuizUseCount(0);
            // Slight starting confidence — better than 0 so the chat can
            // use them, but the user's own uploads outrank.
            e.setCertaintyScore(0.6);
            e.setReviewRequired(false);
            e.setHasConflict(false);
            e.setSeed(true);
            rows.add(e);
        }
        wikiJpa.saveAll(rows);
        log.info("[Seed] subject={} avatar={} pages={}",
                subject, avatarId, rows.size());
        return rows.size();
    }

    private List<SeedPage> loadPack(String subject) {
        String resourcePath = "seed/" + subject.toUpperCase() + ".json";
        try (InputStream in =
                new ClassPathResource(resourcePath).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            JsonNode arr = root.path("pages");
            if (!arr.isArray()) return List.of();
            List<SeedPage> out = new ArrayList<>();
            for (JsonNode n : arr) {
                String slug = n.path("slug").asText("");
                String title = n.path("title").asText("");
                String content = n.path("content").asText("");
                if (slug.isBlank() || title.isBlank() || content.isBlank()) continue;
                out.add(new SeedPage(slug, title, content));
            }
            return out;
        } catch (Exception e) {
            log.debug("[Seed] no resource for {}: {}", resourcePath, e.getMessage());
            return List.of();
        }
    }

    private record SeedPage(String slug, String title, String content) {}
}
