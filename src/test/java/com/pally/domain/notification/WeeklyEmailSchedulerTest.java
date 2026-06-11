package com.pally.domain.notification;

import com.pally.domain.progress.WeeklyReportService;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeeklyEmailSchedulerTest {

    @Mock private UserJpaRepository userRepo;
    @Mock private ActivityLogJpaRepository activityRepo;
    @Mock private WeeklyReportService weeklyReportService;
    @Mock private EmailService emailService;

    private WeeklyEmailScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WeeklyEmailScheduler(userRepo, activityRepo, weeklyReportService, emailService);
    }

    @Test
    void sendWeeklyReports_noParents_sendsNothing() {
        when(userRepo.findByAccountType("PARENT")).thenReturn(List.of());

        scheduler.sendWeeklyReports();

        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void sendWeeklyReports_parentWithActiveChild_sendsEmail() {
        UserJpaEntity parent = new UserJpaEntity();
        parent.setId("p-1");
        parent.setEmail("parent@test.com");
        parent.setAccountType("PARENT");

        UserJpaEntity child = new UserJpaEntity();
        child.setId("c-1");
        child.setChildName("Alice");
        child.setParentId("p-1");
        child.setStreakDays(5);

        when(userRepo.findByAccountType("PARENT")).thenReturn(List.of(parent));
        when(userRepo.findByParentId("p-1")).thenReturn(List.of(child));
        when(activityRepo.sumMinutesBetween(eq("c-1"), any(), any())).thenReturn(45);
        when(activityRepo.countSince(eq("c-1"), any())).thenReturn(3);
        when(activityRepo.sumXpSince(eq("c-1"), any())).thenReturn(120);
        when(weeklyReportService.computeSubjectMastery("c-1")).thenReturn(List.of());

        scheduler.sendWeeklyReports();

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(eq("parent@test.com"), anyString(), htmlCaptor.capture());

        String html = htmlCaptor.getValue();
        assertThat(html).contains("Alice");
        assertThat(html).contains("45");  // minutes
        assertThat(html).contains("+120"); // XP
    }

    @Test
    void sendWeeklyReports_childWithNoActivity_skipsEmail() {
        UserJpaEntity parent = new UserJpaEntity();
        parent.setId("p-2");
        parent.setEmail("parent2@test.com");
        parent.setAccountType("PARENT");

        UserJpaEntity child = new UserJpaEntity();
        child.setId("c-2");
        child.setParentId("p-2");

        when(userRepo.findByAccountType("PARENT")).thenReturn(List.of(parent));
        when(userRepo.findByParentId("p-2")).thenReturn(List.of(child));
        when(activityRepo.sumMinutesBetween(eq("c-2"), any(), any())).thenReturn(0);

        scheduler.sendWeeklyReports();

        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void sendWeeklyReports_parentWithNoEmail_skipsEmail() {
        UserJpaEntity parent = new UserJpaEntity();
        parent.setId("p-3");
        parent.setEmail(null);
        parent.setAccountType("PARENT");

        when(userRepo.findByAccountType("PARENT")).thenReturn(List.of(parent));

        scheduler.sendWeeklyReports();

        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void sendReportForParent_multipleChildren_includesAllActive() {
        UserJpaEntity parent = new UserJpaEntity();
        parent.setId("p-4");
        parent.setEmail("multi@test.com");
        parent.setAccountType("PARENT");

        UserJpaEntity child1 = new UserJpaEntity();
        child1.setId("c-3");
        child1.setChildName("Bob");
        child1.setParentId("p-4");

        UserJpaEntity child2 = new UserJpaEntity();
        child2.setId("c-4");
        child2.setChildName("Carol");
        child2.setParentId("p-4");

        when(userRepo.findByParentId("p-4")).thenReturn(List.of(child1, child2));
        when(activityRepo.sumMinutesBetween(eq("c-3"), any(), any())).thenReturn(30);
        when(activityRepo.sumMinutesBetween(eq("c-4"), any(), any())).thenReturn(15);
        when(activityRepo.countSince(eq("c-3"), any())).thenReturn(2);
        when(activityRepo.countSince(eq("c-4"), any())).thenReturn(1);
        when(activityRepo.sumXpSince(eq("c-3"), any())).thenReturn(80);
        when(activityRepo.sumXpSince(eq("c-4"), any())).thenReturn(40);
        when(weeklyReportService.computeSubjectMastery(anyString())).thenReturn(List.of());

        int sent = scheduler.sendReportForParent(parent);

        assertThat(sent).isEqualTo(1);
        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(eq("multi@test.com"), anyString(), htmlCaptor.capture());
        String html = htmlCaptor.getValue();
        assertThat(html).contains("Bob");
        assertThat(html).contains("Carol");
    }
}
