package com.pally.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard (dependency-free, no ArchUnit): the domain layer must NOT
 * import infrastructure persistence (JPA entities / *JpaRepository) — depend on
 * domain repository PORTS instead. 42 legacy offenders are allow-listed so the
 * test is GREEN today; the point is NO NEW violations. As each offender migrates
 * to a port, delete its line here — the allow-list must only ever SHRINK.
 *
 * <p>History: this leak is the "pattern applied to some, not all" root cause the
 * CLAUDE.md lessons call out. See STEP 3c to shrink this to zero per context.
 */
class DomainLayeringGuardTest {

    private static final Path DOMAIN = Paths.get("src/main/java/com/pally/domain");
    private static final String FORBIDDEN = "import com.pally.infrastructure.persistence";

    /** Legacy offenders (relative to domain/, no .java). Only ever remove lines. */
    private static final Set<String> ALLOW_LIST = Set.of(
            "account/usecase/DeleteAccountUseCase",
            "assignment/AssignmentService",
            "centre/CentreAccessService",
            "centre/CentreInviteService",
            "centre/CentreService",
            "centre/ClassBriefService",
            "centre/ClassCrudService",
            "centre/ClassMembershipService",
            "centre/OrgStaffService",
            "centre/OrgSubscriptionService",
            "centre/PilotPurgeScheduler",
            "consent/ConsentGuard",
            "consent/PendingParentalConsentReaper",
            "demo/DemoLeadService",
            "flag/FeatureFlagService",
            "group/ClassGroupService",
            "group/StudyGroupService",
            "join/CodeResolveService",
            "knowledge/ContentDeduplicator",
            "knowledge/KnowledgeService",
            "knowledge/WikiConflictService",
            "knowledge/usecase/CompileWikiUseCase",
            "knowledge/usecase/DurableCompileStatusStore",
            "knowledge/usecase/WikiPagePersistenceService",
            "notification/RiskAlertScheduler",
            "notification/WeeklyEmailScheduler",
            "organization/ClassEnrollmentService",
            "parent/ParentChildService",
            "progress/ActivityLogService",
            "progress/BadgeService",
            "progress/ProgressService",
            "progress/StreakService",
            "progress/WeeklyReportService",
            "progress/XpService",
            "progress/usecase/GetProgressUseCase",
            "quiz/QuizService",
            "quiz/usecase/SubmitQuizAnswersUseCase",
            "referral/ReferralService",
            "review/ReviewRequestService",
            "shop/CharacterShopService",
            "shop/PowerupService",
            "subscription/PremiumService");

    @Test
    void domain_doesNotImportInfrastructurePersistence_exceptKnownLegacy() throws IOException {
        assertThat(DOMAIN).exists();
        Set<String> newViolations = new TreeSet<>();
        try (Stream<Path> files = Files.walk(DOMAIN)) {
            List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path f : javaFiles) {
                String body = Files.readString(f);
                if (body.contains(FORBIDDEN)) {
                    String rel = DOMAIN.relativize(f).toString()
                            .replace(java.io.File.separatorChar, '/')
                            .replaceAll("\\.java$", "");
                    if (!ALLOW_LIST.contains(rel)) newViolations.add(rel);
                }
            }
        }
        assertThat(newViolations)
                .as("NEW domain→infrastructure.persistence imports (add a PORT instead; "
                        + "do NOT extend the allow-list)")
                .isEmpty();
    }
}
