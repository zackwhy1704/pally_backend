package com.pally.api.knowledge;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeMapper;
import com.pally.domain.knowledge.KnowledgeService;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.dto.RelevanceCheckRequest;
import com.pally.domain.knowledge.usecase.CheckRelevanceUseCase;
import com.pally.domain.knowledge.usecase.CompileJobStore;
import com.pally.domain.knowledge.usecase.CompileWikiUseCase;
import com.pally.domain.knowledge.usecase.DeleteFileUseCase;
import com.pally.domain.knowledge.usecase.UploadFileUseCase;
import com.pally.domain.knowledge.usecase.UploadResult;
import com.pally.infrastructure.ai.WikiRecompileScheduler;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KnowledgeService} — ownership/IDOR guards, the centre
 * read-only guard, and the per-endpoint logic. (Moved here from the controller
 * tests in the controller→service refactor; the controller is now thin.)
 *
 * <p>Rule under test: any authenticated user who knows an avatarId must NOT be
 * able to read or modify another user's wiki pages/files — AvatarNotFoundException
 * (→ HTTP 404) is the correct no-leak response.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock UploadFileUseCase uploadFileUseCase;
    @Mock DeleteFileUseCase deleteFileUseCase;
    @Mock CheckRelevanceUseCase checkRelevanceUseCase;
    @Mock CompileWikiUseCase compileWikiUseCase;
    @Mock CompileJobStore compileJobStore;
    @Mock com.pally.domain.knowledge.usecase.DurableCompileStatusStore durableCompileStatusStore;
    @Mock WikiRecompileScheduler recompileScheduler;
    @Mock KnowledgeRepository knowledgeRepository;
    @Mock KnowledgeMapper knowledgeMapper;
    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock AvatarSlotGuard avatarSlotGuard;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;

    KnowledgeService service;

    private static final String OWNER_USER = "user-alice";
    private static final String ATTACKER_USER = "user-bob";
    private static final String AVATAR_ID = "avatar-123";
    private static final String SLUG = "photosynthesis";

    private Avatar ownerAvatar;
    private WikiPage wikiPage;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(
                uploadFileUseCase,
                org.mockito.Mockito.mock(com.pally.domain.knowledge.usecase.CompileChunkUseCase.class),
                org.mockito.Mockito.mock(com.pally.domain.knowledge.usecase.GetChaptersUseCase.class),
                deleteFileUseCase, checkRelevanceUseCase, compileWikiUseCase,
                compileJobStore, durableCompileStatusStore, recompileScheduler, knowledgeRepository,
                knowledgeMapper, wikiRepository, avatarRepository, avatarSlotGuard, wikiPageSourceRepo);
        ownerAvatar = Avatar.create(OWNER_USER, "Zap", Subject.SCIENCE, CharacterType.ZAP);
        wikiPage = WikiPage.create(AVATAR_ID, SLUG, "Photosynthesis",
                "Plants use sunlight to make food.");
    }

    // ── uploadFile ────────────────────────────────────────────────────────────

    @Test
    void uploadFile_validFile_returnsSuccess() {
        MultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "Math content".getBytes());
        when(avatarRepository.findById("avatar-1")).thenReturn(Optional.of(
                Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI)));
        when(uploadFileUseCase.execute(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.Success("file-1", 1, List.of("fractions")));

        UploadResult result = service.uploadFile("user-1", "avatar-1", file, false);

        assertThat(result).isInstanceOf(UploadResult.Success.class);
    }

    @Test
    void uploadFile_relevanceWarning_returnsWarning() {
        MultipartFile file = new MockMultipartFile(
                "file", "recipe.txt", "text/plain", "Pasta recipe".getBytes());
        when(avatarRepository.findById("avatar-1")).thenReturn(Optional.of(
                Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI)));
        when(uploadFileUseCase.execute(anyString(), anyString(), any(MultipartFile.class), anyBoolean()))
                .thenReturn(new UploadResult.RelevanceWarning("file-1", 0.15, "Off topic"));

        UploadResult result = service.uploadFile("user-1", "avatar-1", file, false);

        assertThat(result).isInstanceOf(UploadResult.RelevanceWarning.class);
    }

    // ── listFiles — ownership guard ─────────────────────────────────────────────

    @Test
    void listFiles_avatarNotOwned_throwsAvatarNotFound() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));

        assertThatThrownBy(() -> service.listFiles(ATTACKER_USER, AVATAR_ID))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void listFiles_validOwner_returnsList() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(knowledgeRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of());
        when(knowledgeMapper.toResponseList(anyList())).thenReturn(List.of());

        assertThat(service.listFiles(OWNER_USER, AVATAR_ID)).isEmpty();
    }

    // ── compileStatus ───────────────────────────────────────────────────────────

    @Test
    void compileStatus_noJobFound_returnsNone() {
        when(compileJobStore.findByAvatarId("avatar-1")).thenReturn(null);

        Map<String, Object> body = service.compileStatus("user-1", "avatar-1");

        assertThat(body.get("state")).isEqualTo("NONE");
    }

    @Test
    void compileStatus_readsDurableStore_whenInMemoryEmpty_soCorrectAcrossReplicas() {
        // C2: a different replica has nothing in memory — the durable row must serve
        // the terminal status (incl. failedPages), or the web shows "✓ compiled" on a
        // partial compile.
        when(compileJobStore.findByAvatarId(AVATAR_ID)).thenReturn(null);
        when(durableCompileStatusStore.find(AVATAR_ID)).thenReturn(Optional.of(
                new com.pally.domain.knowledge.usecase.DurableCompileStatusStore.DurableStatus(
                        "DONE", 6, 8, 2,
                        java.util.List.of(
                                new com.pally.domain.knowledge.usecase.FailedPage("osmosis", "x"),
                                new com.pally.domain.knowledge.usecase.FailedPage("mitosis", "y")))));

        Map<String, Object> body = service.compileStatus("user-1", AVATAR_ID);

        assertThat(body.get("state")).isEqualTo("DONE");
        assertThat(body.get("pagesFailed")).isEqualTo(2);
        assertThat(body.get("failedPages")).isNotNull();
    }

    // ── A1: explicit compile shares the scheduler's per-avatar gate ─────────────

    @Test
    void compileWiki_whenCompileAlreadyInFlight_returns202_marksDirty_noParallelCompile() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(compileWikiUseCase.shouldCompileAsync(AVATAR_ID)).thenReturn(false);
        when(recompileScheduler.tryBeginExternalCompile(AVATAR_ID)).thenReturn(false); // gate held

        KnowledgeService.CompileOutcome outcome = service.compileWiki(OWNER_USER, AVATAR_ID);

        assertThat(outcome.async()).isTrue();
        assertThat(outcome.asyncBody()).containsEntry("inProgress", true);
        verify(recompileScheduler).markDirty(AVATAR_ID);
        verify(compileWikiUseCase, never()).executeBounded(anyString());
        verify(recompileScheduler, never()).endExternalCompile(anyString());
    }

    @Test
    void compileWiki_whenGateFree_compilesAndAlwaysReleasesGate() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(compileWikiUseCase.shouldCompileAsync(AVATAR_ID)).thenReturn(false);
        when(recompileScheduler.tryBeginExternalCompile(AVATAR_ID)).thenReturn(true);
        when(compileWikiUseCase.executeBounded(AVATAR_ID))
                .thenReturn(new CompileWikiUseCase.CompileResult(2, 0, java.util.List.of("A", "B")));

        KnowledgeService.CompileOutcome outcome = service.compileWiki(OWNER_USER, AVATAR_ID);

        assertThat(outcome.async()).isFalse();
        verify(compileWikiUseCase).executeBounded(AVATAR_ID);
        verify(recompileScheduler).endExternalCompile(AVATAR_ID);
    }

    // ── checkRelevance — ownership guard ────────────────────────────────────────

    @Test
    void checkRelevance_avatarNotOwned_throwsAvatarNotFound() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        var request = new RelevanceCheckRequest("sample text");

        assertThatThrownBy(() -> service.checkRelevance(ATTACKER_USER, AVATAR_ID, request))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    // ── getWikiPage — ownership guard + not-found ───────────────────────────────

    @Test
    void getWikiPage_owner_returnsPage() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR_ID, SLUG)).thenReturn(Optional.of(wikiPage));

        assertThat(service.getWikiPage(OWNER_USER, AVATAR_ID, SLUG)).isEqualTo(wikiPage);
    }

    @Test
    void getWikiPage_pageNotFound_throwsBusinessException() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR_ID, "nonexistent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWikiPage(OWNER_USER, AVATAR_ID, "nonexistent"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Wiki page not found");
    }

    @Test
    void getWikiPage_differentUser_throws404() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));

        assertThatThrownBy(() -> service.getWikiPage(ATTACKER_USER, AVATAR_ID, SLUG))
                .isInstanceOf(AvatarNotFoundException.class)
                .hasMessageContaining(AVATAR_ID);
    }

    @Test
    void getWikiPage_unknownAvatarId_throws404() {
        when(avatarRepository.findById("unknown-avatar")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWikiPage(ATTACKER_USER, "unknown-avatar", SLUG))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    // ── applyCorrection — ownership guard ───────────────────────────────────────

    @Test
    void applyCorrection_owner_appliesAndReturnsPage() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR_ID, SLUG)).thenReturn(Optional.of(wikiPage));
        when(wikiRepository.save(wikiPage)).thenReturn(wikiPage);

        assertThat(service.applyCorrection(OWNER_USER, AVATAR_ID, SLUG, "Chlorophyll absorbs light."))
                .isEqualTo(wikiPage);
    }

    @Test
    void applyCorrection_differentUser_throws404() {
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(ownerAvatar));

        assertThatThrownBy(() ->
                service.applyCorrection(ATTACKER_USER, AVATAR_ID, SLUG, "Malicious edit"))
                .isInstanceOf(AvatarNotFoundException.class)
                .hasMessageContaining(AVATAR_ID);
    }

    @Test
    void applyCorrection_unknownAvatarId_throws404() {
        when(avatarRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyCorrection(ATTACKER_USER, "ghost", SLUG, "edit"))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    // ── Centre material read-only guard ─────────────────────────────────────────

    @Test
    void deleteFile_onStudentClassAvatar_isReadOnly403_andUseCaseNotCalled() {
        // A student's class-bound CENTRE_CLASS avatar POINTS at the shared corpus
        // (corpusAvatarId != null) — study-only material.
        Avatar classAvatar = Avatar.create("student-1", "P4 Math",
                Subject.MATHS, CharacterType.MOCHI);
        classAvatar.markCentreClassAvatar();
        classAvatar.setClassId("class-1");
        classAvatar.setCorpusAvatarId("corpus-1");
        when(avatarRepository.findById(classAvatar.getId()))
                .thenReturn(Optional.of(classAvatar));

        assertThatThrownBy(() ->
                service.deleteFile("student-1", classAvatar.getId(), "file-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CENTRE_MATERIAL_READ_ONLY");
        verify(deleteFileUseCase, never()).execute(anyString(), anyString());
    }

    @Test
    void deleteFile_onTeacherClassCorpus_isMutable_andUseCaseCalled() {
        // The class CORPUS avatar (the one teachers upload/compile into) carries a
        // classId but NO corpusAvatarId — it IS the corpus, not derived from one. It
        // must stay mutable for its owner, else no class content can ever be built.
        Avatar corpus = Avatar.create("teacher-1", "P4 Math Corpus",
                Subject.MATHS, CharacterType.MOCHI);
        corpus.markCentreClassAvatar();
        corpus.setClassId("class-1");
        // corpusAvatarId stays null — this avatar is the corpus.
        when(avatarRepository.findById(corpus.getId()))
                .thenReturn(Optional.of(corpus));

        service.deleteFile("teacher-1", corpus.getId(), "file-1");

        verify(deleteFileUseCase).execute("file-1", "teacher-1");
    }
}
