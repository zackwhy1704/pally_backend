package com.pally.api.group;

import com.pally.domain.group.StudyGroupService;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.progress.XpService;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.ai.ModerationService;
import com.pally.infrastructure.persistence.group.GroupMemberJpaEntity;
import com.pally.infrastructure.persistence.group.GroupMemberJpaRepository;
import com.pally.infrastructure.persistence.group.GroupReportJpaEntity;
import com.pally.infrastructure.persistence.group.GroupReportJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaEntity;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaRepository;
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaEntity;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModerationService.screenInput now gates the 3 write paths that used to skip
 * it entirely: createGroup(name), shareNote(page content), report(details).
 * Block condition mirrors SendMessageUseCase: flagged() && isHighSeverity().
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudyGroupModerationTest {

    @Mock StudyGroupJpaRepository groupRepo;
    @Mock GroupMemberJpaRepository memberRepo;
    @Mock GroupSharedNoteJpaRepository sharedNoteRepo;
    @Mock GroupReportJpaRepository reportRepo;
    @Mock GroupSystemPostJpaRepository systemPostRepo;
    @Mock WikiPageJpaRepository wikiPageRepo;
    @Mock RelevancePort relevancePort;
    @Mock UserJpaRepository userRepo;
    @Mock PremiumService premiumService;
    @Mock XpService xpService;
    @Mock ModerationService moderationService;

    StudyGroupService service;

    private static final String USER = "user-1";
    private static final String GROUP_ID = "group-1";
    private static final String PAGE_ID = "page-1";

    private static final ModerationService.ModerationResult SAFE =
            new ModerationService.ModerationResult(false, "SAFE", "SAFE", null);
    private static final ModerationService.ModerationResult BLOCKED =
            new ModerationService.ModerationResult(true, "BULLYING", "HIGH", "Let's keep it kind.");

    @BeforeEach
    void setUp() {
        service = new StudyGroupService(groupRepo, memberRepo, sharedNoteRepo, reportRepo,
                systemPostRepo, wikiPageRepo, relevancePort, userRepo, premiumService, xpService,
                moderationService);
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.PRO);
        when(groupRepo.existsByInviteCode(anyString())).thenReturn(false);
        when(groupRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepo.save(any())).thenReturn(new GroupMemberJpaEntity());
        when(memberRepo.findByGroupId(anyString())).thenReturn(List.of());
        when(memberRepo.existsByGroupIdAndUserId(GROUP_ID, USER)).thenReturn(true);
    }

    private StudyGroupJpaEntity group(String subject) {
        StudyGroupJpaEntity g = new StudyGroupJpaEntity();
        g.setId(GROUP_ID);
        g.setName("Bio Buddies");
        g.setSubject(subject);
        return g;
    }

    private WikiPageJpaEntity page(String content) {
        WikiPageJpaEntity p = new WikiPageJpaEntity();
        p.setId(PAGE_ID);
        p.setAvatarId("avatar-1");
        p.setTitle("Photosynthesis");
        p.setContent(content);
        return p;
    }

    // ── 1. createGroup(name) ────────────────────────────────────────────

    @Test
    void createGroup_nameFlaggedHighSeverity_throws422_neverPersists() {
        when(moderationService.screenInput(eq(USER), any(), any(), eq("Bad Name"), eq("en")))
                .thenReturn(BLOCKED);

        assertThatThrownBy(() -> service.createGroup(USER, Map.of("name", "Bad Name")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422);
        verify(groupRepo, never()).save(any());
    }

    @Test
    void createGroup_nameSafe_persistsNormally() {
        when(moderationService.screenInput(eq(USER), any(), any(), eq("Bio Buddies"), eq("en")))
                .thenReturn(SAFE);

        var resp = service.createGroup(USER, Map.of("name", "Bio Buddies"));
        assertThat(resp).containsEntry("name", "Bio Buddies");
        verify(groupRepo).save(any());
    }

    // ── 2. shareNote(page content) ──────────────────────────────────────

    @Test
    void shareNote_contentFlaggedHighSeverity_throws422_neverPersists_evenWhenRelevanceWouldPass() {
        when(groupRepo.findById(GROUP_ID)).thenReturn(Optional.of(group("Biology")));
        when(wikiPageRepo.findById(PAGE_ID)).thenReturn(Optional.of(page("unsafe content")));
        when(moderationService.screenInput(eq(USER), eq("avatar-1"), any(),
                eq("unsafe content"), any())).thenReturn(BLOCKED);
        // Relevance would have passed — proves moderation blocks INDEPENDENTLY.
        when(relevancePort.check(any(), any(), any()))
                .thenReturn(new RelevanceScore(0.9, "on topic"));

        assertThatThrownBy(() -> service.shareNote(USER, GROUP_ID, Map.of("wikiPageId", PAGE_ID)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422);
        verify(sharedNoteRepo, never()).save(any());
    }

    @Test
    void shareNote_moderationRunsEvenWhenGroupHasNoSubject_fixesTheSilentBypass() {
        // group.getSubject() is blank → applyShareRelevance short-circuits to OK
        // with ZERO screening. Moderation must run anyway.
        when(groupRepo.findById(GROUP_ID)).thenReturn(Optional.of(group(null)));
        when(wikiPageRepo.findById(PAGE_ID)).thenReturn(Optional.of(page("unsafe content")));
        when(moderationService.screenInput(eq(USER), eq("avatar-1"), any(),
                eq("unsafe content"), any())).thenReturn(BLOCKED);

        assertThatThrownBy(() -> service.shareNote(USER, GROUP_ID, Map.of("wikiPageId", PAGE_ID)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422);
        verify(sharedNoteRepo, never()).save(any());
        // relevancePort must never even be consulted — subject was blank.
        verify(relevancePort, never()).check(any(), any(), any());
    }

    @Test
    void shareNote_relevanceStillBlocksSeparately_whenModerationIsSafe() {
        // Confirms the fix didn't remove the pre-existing relevance gate.
        when(groupRepo.findById(GROUP_ID)).thenReturn(Optional.of(group("Biology")));
        when(wikiPageRepo.findById(PAGE_ID)).thenReturn(Optional.of(page("off topic content")));
        when(moderationService.screenInput(any(), any(), any(), any(), any())).thenReturn(SAFE);
        when(relevancePort.check(any(), any(), any()))
                .thenReturn(new RelevanceScore(0.05, "unrelated"));

        assertThatThrownBy(() -> service.shareNote(USER, GROUP_ID, Map.of("wikiPageId", PAGE_ID)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422);
        verify(sharedNoteRepo, never()).save(any());
    }

    @Test
    void shareNote_safeAndOnTopic_persists() {
        when(groupRepo.findById(GROUP_ID)).thenReturn(Optional.of(group("Biology")));
        when(wikiPageRepo.findById(PAGE_ID)).thenReturn(Optional.of(page("photosynthesis notes")));
        when(moderationService.screenInput(any(), any(), any(), any(), any())).thenReturn(SAFE);
        when(relevancePort.check(any(), any(), any()))
                .thenReturn(new RelevanceScore(0.9, "on topic"));
        when(sharedNoteRepo.existsByGroupIdAndWikiPageIdAndSharedBy(any(), any(), any()))
                .thenReturn(false);

        var resp = service.shareNote(USER, GROUP_ID, Map.of("wikiPageId", PAGE_ID));
        assertThat(resp).containsEntry("relevanceStatus", "OK");
        verify(sharedNoteRepo).save(any());
    }

    // ── 3. report(details) ──────────────────────────────────────────────

    @Test
    void report_detailsOverLengthCap_throws400_neverPersists() {
        String tooLong = "x".repeat(501);
        assertThatThrownBy(() -> service.report(USER, GROUP_ID,
                Map.of("reason", "spam", "targetUserId", "other-user", "details", tooLong)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
        verify(reportRepo, never()).save(any());
        verify(moderationService, never()).screenInput(any(), any(), any(), any(), any());
    }

    @Test
    void report_detailsFlaggedHighSeverity_throws422_neverPersists() {
        when(moderationService.screenInput(eq(USER), any(), any(), eq("unsafe details"), eq("en")))
                .thenReturn(BLOCKED);

        assertThatThrownBy(() -> service.report(USER, GROUP_ID,
                Map.of("reason", "spam", "targetUserId", "other-user", "details", "unsafe details")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422);
        verify(reportRepo, never()).save(any());
    }

    @Test
    void report_detailsSafeAndUnderCap_persistsNormally() {
        when(moderationService.screenInput(eq(USER), any(), any(), eq("they copied my answer"), eq("en")))
                .thenReturn(SAFE);

        service.report(USER, GROUP_ID, Map.of(
                "reason", "spam", "targetUserId", "other-user", "details", "they copied my answer"));

        ArgumentCaptor<GroupReportJpaEntity> cap = ArgumentCaptor.forClass(GroupReportJpaEntity.class);
        verify(reportRepo).save(cap.capture());
        assertThat(cap.getValue().getDetails()).isEqualTo("they copied my answer");
    }
}
