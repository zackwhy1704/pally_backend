package com.pally.api.avatar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.api.avatar.dto.AvatarResponse;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link AvatarMapper} surfaces the per-class {@code mochiConfig} on
 * CENTRE_CLASS avatars (and only those), for both the single-avatar and list paths.
 */
@ExtendWith(MockitoExtension.class)
class AvatarMapperTest {

    private static final String CLASS_ID = "class-1";
    private static final String CONFIG_JSON =
            "{\"body\":4,\"accessory\":\"crown\",\"aura\":\"sparkle\"}";

    @Mock private KnowledgeRepository knowledgeRepository;
    @Mock private OrgClassJpaRepository orgClassRepository;

    private AvatarMapper mapper;

    @BeforeEach
    void setUp() {
        // No knowledge files in these tests; fileCount is not the subject under test.
        lenient().when(knowledgeRepository.findByAvatarId(any())).thenReturn(List.of());
        mapper = new AvatarMapper(knowledgeRepository, orgClassRepository, new ObjectMapper());
    }

    private Avatar classAvatar() {
        Avatar a = Avatar.create("student-1", "Algebra Club", Subject.MATHS, CharacterType.MOCHI);
        a.markCentreClassAvatar();
        a.setClassId(CLASS_ID);
        return a;
    }

    private Avatar personalAvatar() {
        return Avatar.create("user-1", "My Buddy", Subject.SCIENCE, CharacterType.MOCHI);
    }

    private OrgClassJpaEntity classWithConfig() {
        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setId(CLASS_ID);
        cls.setMochiConfig(CONFIG_JSON);
        return cls;
    }

    @Test
    void singleGet_centreClassAvatarWithSavedConfig_returnsMochiConfig() {
        when(orgClassRepository.findById(CLASS_ID)).thenReturn(Optional.of(classWithConfig()));

        AvatarResponse resp = mapper.toResponse(classAvatar());

        assertThat(resp.mochiConfig()).isNotNull();
        assertThat(resp.mochiConfig().body()).isEqualTo(4);
        assertThat(resp.mochiConfig().accessory()).isEqualTo("crown");
        assertThat(resp.mochiConfig().aura()).isEqualTo("sparkle");
    }

    @Test
    void singleGet_personalAvatar_mochiConfigIsNullAndNoClassLookup() {
        AvatarResponse resp = mapper.toResponse(personalAvatar());

        assertThat(resp.mochiConfig()).isNull();
        // PERSONAL avatars must never trigger an org_class query.
        verify(orgClassRepository, never()).findById(any());
    }

    @Test
    void response_carriesContentLanguage_defaultEn_andZh() {
        assertThat(mapper.toResponse(personalAvatar()).contentLanguage()).isEqualTo("en");
        Avatar zh = personalAvatar();
        zh.setContentLanguage("zh");
        assertThat(mapper.toResponse(zh).contentLanguage()).isEqualTo("zh");
    }

    @Test
    void singleGet_centreClassAvatarWithNoConfig_mochiConfigIsNull() {
        OrgClassJpaEntity bare = new OrgClassJpaEntity();
        bare.setId(CLASS_ID); // mochiConfig stays null
        when(orgClassRepository.findById(CLASS_ID)).thenReturn(Optional.of(bare));

        AvatarResponse resp = mapper.toResponse(classAvatar());

        assertThat(resp.mochiConfig()).isNull();
    }

    @Test
    void singleGet_corpusAvatar_resolvesConfigByCorpusAvatarId() {
        // Corpus avatar: CENTRE_CLASS but classId == null. The owner sees this in
        // their app; it must still get the class's Mochi config (resolved by
        // corpusAvatarId), not fall back to the plain default.
        Avatar corpus = Avatar.create("owner-1", "Class Corpus",
                Subject.MATHS, CharacterType.MOCHI);
        corpus.markCentreClassAvatar(); // classId stays null
        OrgClassJpaEntity cls = classWithConfig();
        cls.setCorpusAvatarId(corpus.getId());
        when(orgClassRepository.findByCorpusAvatarId(corpus.getId()))
                .thenReturn(Optional.of(cls));

        AvatarResponse resp = mapper.toResponse(corpus);

        assertThat(resp.mochiConfig()).isNotNull();
        assertThat(resp.mochiConfig().accessory()).isEqualTo("crown");
    }

    @Test
    void list_corpusAvatar_resolvesConfigByCorpusAvatarId() {
        Avatar corpus = Avatar.create("owner-1", "Class Corpus",
                Subject.MATHS, CharacterType.MOCHI);
        corpus.markCentreClassAvatar();
        OrgClassJpaEntity cls = classWithConfig();
        cls.setCorpusAvatarId(corpus.getId());
        when(orgClassRepository.findByCorpusAvatarIdIn(any()))
                .thenReturn(List.of(cls));

        List<AvatarResponse> out = mapper.toResponseList(List.of(corpus));

        assertThat(out.get(0).mochiConfig()).isNotNull();
        assertThat(out.get(0).mochiConfig().accessory()).isEqualTo("crown");
    }

    @Test
    void list_mixedAvatars_classGetsConfigPersonalGetsNull_andBatchesLookup() {
        when(orgClassRepository.findAllById(any())).thenReturn(List.of(classWithConfig()));

        List<AvatarResponse> out = mapper.toResponseList(List.of(personalAvatar(), classAvatar()));

        assertThat(out.get(0).mochiConfig()).isNull();           // personal
        assertThat(out.get(1).mochiConfig()).isNotNull();        // class
        assertThat(out.get(1).mochiConfig().body()).isEqualTo(4);
        // Batched: the list path uses findAllById, never per-avatar findById.
        verify(orgClassRepository, never()).findById(any());
    }

    @Test
    void list_noCentreClassAvatars_skipsClassQueryEntirely() {
        List<AvatarResponse> out = mapper.toResponseList(List.of(personalAvatar(), personalAvatar()));

        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(r -> assertThat(r.mochiConfig()).isNull());
        verify(orgClassRepository, never()).findAllById(any());
        verify(orgClassRepository, never()).findById(any());
    }

    // ── Segmented-compile honesty: derived AvatarResponse surface ────────────────────

    @Test
    void derivation_pendingChunkFileAndNonReadyBrain_awaitingChapterSelectionTrue() {
        // Late-segmentation state: a non-READY brain with a PENDING_CHUNK chapter to pick.
        Avatar a = personalAvatar();
        a.setBrainState(Avatar.BrainState.PENDING_RECOMPILE); // non-READY
        KnowledgeFile parent = KnowledgeFile.create(
                a.getUserId(), a.getUserId(), "book.pdf", "key", KnowledgeFile.UploadType.PDF);
        parent.markSegmented();
        KnowledgeFile chunk = KnowledgeFile.createChunk(parent, "Chapter 1", 1, 20, 20, "text");
        when(knowledgeRepository.findByAvatarId(a.getId())).thenReturn(List.of(parent, chunk));

        AvatarResponse resp = mapper.toResponse(a);

        assertThat(resp.awaitingChapterSelection()).isTrue();
        assertThat(resp.pendingChapterCount()).isGreaterThanOrEqualTo(1);
        // Brain state stays one of the existing enum values — no new enum leaked.
        assertThat(resp.brainState()).isEqualTo("PENDING_RECOMPILE");
        assertThat(resp.compileFailureReason()).isNull();
    }

    @Test
    void derivation_readyAvatarWithNoPendingFiles_awaitingFalseCountZeroReasonNull() {
        // Regression: a normal READY avatar carries none of the new honesty flags.
        Avatar a = personalAvatar(); // create() defaults to BrainState.READY
        // findByAvatarId default (from @BeforeEach) is an empty file list.

        AvatarResponse resp = mapper.toResponse(a);

        assertThat(resp.awaitingChapterSelection()).isFalse();
        assertThat(resp.pendingChapterCount()).isEqualTo(0);
        assertThat(resp.compileFailureReason()).isNull();
        assertThat(resp.compileFailureKind()).isNull();
    }

    @Test
    void derivation_allFailedNoPagesNonReadyBrain_surfacesExtractionFailureReason() {
        Avatar a = personalAvatar();
        a.setBrainState(Avatar.BrainState.PENDING_RECOMPILE); // non-READY
        a.setWikiPageCount(0); // no real content
        KnowledgeFile failed = KnowledgeFile.create(
                a.getUserId(), a.getUserId(), "blurry.pdf", "key", KnowledgeFile.UploadType.PDF);
        failed.markFailed();
        when(knowledgeRepository.findByAvatarId(a.getId())).thenReturn(List.of(failed));

        AvatarResponse resp = mapper.toResponse(a);

        // A genuine extraction failure gets the "couldn't read enough text" copy, not the
        // subject-mismatch copy.
        assertThat(resp.compileFailureReason()).contains("couldn't read enough text");
        assertThat(resp.compileFailureReason()).doesNotContain("match your class's subject");
        assertThat(resp.compileFailureKind()).isEqualTo("FAILED");
        assertThat(resp.awaitingChapterSelection()).isFalse();
        assertThat(resp.pendingChapterCount()).isEqualTo(0);
    }

    @Test
    void derivation_allIrrelevantNoPagesNonReadyBrain_surfacesSubjectMismatchReason_notExtractionCopy() {
        // The Sales Game incident: a file that EXTRACTED fine (~340k chars) but scored 0.05
        // relevance to a Maths class was marked IRRELEVANT. It must NOT be told "we couldn't
        // read enough text" — that's the wizard lying about a status the pipeline knows.
        // Fail-without-fix: pre-change every IRRELEVANT-only avatar got the extraction string.
        Avatar a = personalAvatar();
        a.setBrainState(Avatar.BrainState.PENDING_RECOMPILE); // non-READY
        a.setWikiPageCount(0);
        KnowledgeFile irrelevant = KnowledgeFile.create(
                a.getUserId(), a.getUserId(), "sales_game.pdf", "key", KnowledgeFile.UploadType.PDF);
        irrelevant.markIrrelevant();
        when(knowledgeRepository.findByAvatarId(a.getId())).thenReturn(List.of(irrelevant));

        AvatarResponse resp = mapper.toResponse(a);

        assertThat(resp.compileFailureReason()).contains("match your class's subject");
        assertThat(resp.compileFailureReason()).doesNotContain("couldn't read enough text");
        // The machine-readable kind lets the client pick the re-upload affordance (recompile
        // is a dead end for a relevance rejection) without string-matching the copy.
        assertThat(resp.compileFailureKind()).isEqualTo("IRRELEVANT");
    }

    @Test
    void derivation_mixedFailedAndIrrelevant_namesBothCauses_notASingleBlanketString() {
        // Mixed batch: one file failed extraction, one is subject-mismatched. A single blanket
        // sentence is wrong for one of them either way — the message must acknowledge both.
        Avatar a = personalAvatar();
        a.setBrainState(Avatar.BrainState.PENDING_RECOMPILE); // non-READY
        a.setWikiPageCount(0);
        KnowledgeFile failed = KnowledgeFile.create(
                a.getUserId(), a.getUserId(), "blurry.pdf", "key", KnowledgeFile.UploadType.PDF);
        failed.markFailed();
        KnowledgeFile irrelevant = KnowledgeFile.create(
                a.getUserId(), a.getUserId(), "sales_game.pdf", "key", KnowledgeFile.UploadType.PDF);
        irrelevant.markIrrelevant();
        when(knowledgeRepository.findByAvatarId(a.getId())).thenReturn(List.of(failed, irrelevant));

        AvatarResponse resp = mapper.toResponse(a);

        // Both causes named — neither file is mislabeled by a blanket string.
        assertThat(resp.compileFailureReason()).contains("couldn't be read");
        assertThat(resp.compileFailureReason()).contains("match your class's subject");
        assertThat(resp.compileFailureKind()).isEqualTo("MIXED");
    }
}
