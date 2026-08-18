package com.pally.domain.syllabus;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.module.LearningModule;
import com.pally.domain.module.LearningModuleRepository;
import com.pally.domain.module.ModuleContentGenerator;
import com.pally.domain.module.ModuleContentItem;
import com.pally.domain.module.ModuleContentItemRepository;
import com.pally.domain.syllabus.dto.PackBrowseView;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves/creates {@code syllabus_content_pack}s and generates their content through
 * the SAME {@link ModuleContentGenerator} pipeline used for teacher-uploaded material —
 * no second generation path. The only difference is the source: a synthetic
 * {@link WikiPage} built from OER-grounded text, owned by a hidden {@code SYLLABUS_PACK}
 * avatar, instead of an uploaded file.
 *
 * <p>Safety model (mirrors {@code ContentReviewPortAdapter} but platform-scoped instead
 * of class-scoped, since a syllabus pack has no org/class): generated items land DRAFT
 * ({@link ModuleContentGenerator#generateAsPack}) and only become servable
 * (LIVE/APPROVED, per the existing {@code ModuleContentItemRepository.SERVABLE_STATUSES}
 * allow-list) after an explicit platform-admin {@link #approveItems} call, scoped to the
 * calling pack's own avatar so one pack can never approve another's — or a real class's —
 * items.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyllabusContentPackService {

    /**
     * Fixed platform-owned account that owns every syllabus-pack avatar (seeded by V129).
     * {@code avatars.user_id} has no FK constraint, but every existing AvatarKind still
     * populates it with a real, traceable owner — this is that owner for content with no
     * human owner.
     */
    public static final String PLATFORM_SYSTEM_USER_ID = "platform-syllabus-content-system";

    private final SyllabusContentPackRepository packRepository;
    private final AvatarRepository avatarRepository;
    private final ModuleContentGenerator moduleContentGenerator;
    private final LearningModuleRepository learningModuleRepository;
    private final ModuleContentItemRepository itemRepository;

    /**
     * Resolves (creating on first use) the pack + its hidden avatar for
     * (syllabusCode, topicTag). Race-safe on the DB's unique (syllabus_code, topic_tag)
     * constraint — mirrors {@code MarkingCorpusService#resolveOrCreate}.
     */
    @Transactional
    public SyllabusContentPack resolveOrCreatePack(
            String syllabusCode, String topicTag, Subject subject, String sourceLicenseNote) {
        if (syllabusCode == null || syllabusCode.isBlank()) {
            throw new BusinessException("syllabusCode is required", 400);
        }
        if (topicTag == null || topicTag.isBlank()) {
            throw new BusinessException("topicTag is required", 400);
        }

        Optional<SyllabusContentPack> existing =
                packRepository.findBySyllabusCodeAndTopicTag(syllabusCode, topicTag);
        if (existing.isPresent()) {
            return existing.get();
        }

        Avatar avatar = Avatar.create(
                PLATFORM_SYSTEM_USER_ID, syllabusCode + " / " + topicTag, subject,
                CharacterType.MOCHI, null, null);
        avatar.markSyllabusPack();
        Avatar savedAvatar = avatarRepository.save(avatar);

        SyllabusContentPack pack = new SyllabusContentPack(
                IdGenerator.newId(), syllabusCode, topicTag, savedAvatar.getId(),
                PackStatus.DRAFT.name(), sourceLicenseNote, Instant.now());
        try {
            SyllabusContentPack saved = packRepository.save(pack);
            log.info("[SyllabusPack] created pack syllabus={} topic={} avatar={}",
                    syllabusCode, topicTag, savedAvatar.getId());
            return saved;
        } catch (DataIntegrityViolationException race) {
            // A concurrent first-create for the same (syllabusCode, topicTag) won the
            // unique key. Discard our orphan avatar and use the winner's mapping.
            log.info("[SyllabusPack] lost create race syllabus={} topic={} — using existing",
                    syllabusCode, topicTag);
            try {
                avatarRepository.deleteById(savedAvatar.getId());
            } catch (Exception cleanup) {
                log.warn("[SyllabusPack] orphan avatar cleanup failed avatar={}: {}",
                        savedAvatar.getId(), cleanup.getMessage());
            }
            return packRepository.findBySyllabusCodeAndTopicTag(syllabusCode, topicTag)
                    .orElseThrow(() -> new BusinessException("Pack creation race unresolved", 500));
        }
    }

    /**
     * Generates one module of DRAFT content into the pack from a synthetic,
     * OER-grounded {@link WikiPage}. Reuses {@link ModuleContentGenerator} unforked —
     * items land DRAFT, never auto-servable (see class javadoc).
     */
    @Transactional
    public LearningModule generateModuleForPack(SyllabusContentPack pack, WikiPage syntheticPage) {
        Avatar packAvatar = avatarRepository.findById(pack.avatarId())
                .orElseThrow(() -> new BusinessException("Pack avatar not found", 404));
        return moduleContentGenerator.generateAsPack(packAvatar, syntheticPage);
    }

    /**
     * Platform-admin only: flips a pack's own DRAFT items to LIVE. Scoped to modules
     * owned by this pack's avatar, exactly like {@code ContentReviewPortAdapter
     * #approveItems} scopes to the caller's class — a pack can never approve another
     * pack's, or a real class's, items by id.
     */
    @Transactional
    public int approveItems(String packId, List<String> itemIds) {
        SyllabusContentPack pack = packRepository.findById(packId)
                .orElseThrow(() -> new BusinessException("Pack not found", 404));
        if (itemIds == null || itemIds.isEmpty()) return 0;

        Set<String> packModuleIds = learningModuleRepository.findByAvatarId(pack.avatarId()).stream()
                .map(LearningModule::getId)
                .collect(Collectors.toSet());

        int approved = 0;
        for (String id : itemIds) {
            ModuleContentItem item = itemRepository.findById(id).orElse(null);
            if (item == null || !packModuleIds.contains(item.getModuleId())) continue;
            if (!"DRAFT".equals(item.getStatus())) continue; // only DRAFT -> LIVE
            item.setStatus("LIVE");
            itemRepository.save(item);
            approved++;
        }
        return approved;
    }

    /** Platform-admin only: flips pack visibility so it can appear in "browse starter content". */
    @Transactional
    public SyllabusContentPack publish(String packId) {
        SyllabusContentPack pack = packRepository.findById(packId)
                .orElseThrow(() -> new BusinessException("Pack not found", 404));
        SyllabusContentPack published = new SyllabusContentPack(
                pack.id(), pack.syllabusCode(), pack.topicTag(), pack.avatarId(),
                PackStatus.PUBLISHED.name(), pack.sourceLicenseNote(), pack.createdAt());
        return packRepository.save(published);
    }

    /**
     * Student-facing browse: PUBLISHED packs that have at least one independently
     * SERVABLE (LIVE/APPROVED) item. A DRAFT pack, or a PUBLISHED pack whose items are
     * still all DRAFT, never appears here — this dual gate (pack_status AND item
     * status) IS the pre-moderated proof, not a single reused flag.
     */
    @Transactional(readOnly = true)
    public List<PackBrowseView> browsePublished(String syllabusCodeFilter) {
        List<SyllabusContentPack> packs = packRepository.findByPackStatus(PackStatus.PUBLISHED.name());
        List<PackBrowseView> views = new ArrayList<>();
        for (SyllabusContentPack pack : packs) {
            if (syllabusCodeFilter != null && !syllabusCodeFilter.isBlank()
                    && !syllabusCodeFilter.equals(pack.syllabusCode())) {
                continue;
            }
            List<LearningModule> modules = learningModuleRepository.findByAvatarId(pack.avatarId());
            int servableCount = 0;
            for (LearningModule module : modules) {
                servableCount += itemRepository
                        .findServableByModuleIdOrderBySortOrder(module.getId()).size();
            }
            if (servableCount == 0) continue; // nothing servable yet — don't advertise an empty pack
            views.add(new PackBrowseView(
                    pack.id(), pack.syllabusCode(), pack.topicTag(), modules.size(), servableCount));
        }
        return views;
    }
}
