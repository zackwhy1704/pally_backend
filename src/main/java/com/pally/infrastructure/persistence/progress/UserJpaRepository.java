package com.pally.infrastructure.persistence.progress;

import com.pally.domain.account.AccountType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, String> {
    Optional<UserJpaEntity> findByEmail(String email);
    boolean existsByEmail(String email);

    /// Admin user lookup — case-insensitive substring match on email OR
    /// display name, across the whole table (not just the loaded page).
    Page<UserJpaEntity> findByEmailContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
            String email, String displayName, Pageable pageable);

    Optional<UserJpaEntity> findByLinkCode(String linkCode);

    List<UserJpaEntity> findByParentId(String parentId);

    List<UserJpaEntity> findByAccountType(AccountType accountType);

    /// Reaper query: accounts stuck in a status (e.g. PENDING_CONSENT) since before
    /// a cutoff — used to delete never-approved under-13 accounts (Part B retention).
    List<UserJpaEntity> findByAccountStatusAndCreatedAtBefore(
            String accountStatus, java.time.Instant createdAt);

    int countByParentId(String parentId);

    Optional<UserJpaEntity> findByReferralCode(String referralCode);

    List<UserJpaEntity> findByCentreId(String centreId);

    List<UserJpaEntity> findByCentreIdAndCohortLabel(
            String centreId, String cohortLabel);

    long countByCentreId(String centreId);

    long countByCentreIdAndCohortLabel(String centreId, String cohortLabel);

    Page<UserJpaEntity> findByCentreId(String centreId, Pageable pageable);

    Page<UserJpaEntity> findByCentreIdAndCohortLabel(
            String centreId, String cohortLabel, Pageable pageable);

    /// Atomic increment of XP + stars in a single UPDATE. Closes the D1
    /// lost-update race where two concurrent credits both read-then-wrote
    /// the same starting balance. Returns the number of rows affected
    /// (1 on success, 0 if the user vanished).
    ///
    /// clearAutomatically=true: evicts the user entity from the JPA first-level
    /// cache AFTER this bulk UPDATE runs, so any subsequent findById in the same
    /// tx (e.g. StreakService.recordActiveDay) loads fresh data from the DB
    /// instead of the stale pre-update snapshot. Without this, the streak save
    /// writes back xp=0 and silently erases the XP credit.
    ///
    /// flushAutomatically=true: flushes any pending dirty entities BEFORE the
    /// UPDATE so in-flight changes aren't lost when the cache is cleared.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE users
               SET xp = xp + :xpDelta,
                   stars = stars + :starsDelta
             WHERE id = :userId
            """, nativeQuery = true)
    int creditXpAndStars(@Param("userId") String userId,
                         @Param("xpDelta") int xpDelta,
                         @Param("starsDelta") int starsDelta);

    @Modifying
    @Query(value = "UPDATE users SET level = :level WHERE id = :userId",
            nativeQuery = true)
    int updateLevel(@Param("userId") String userId,
                    @Param("level") int level);

    /// Atomic conditional spend of stars + freeze grant. The condition is
    /// enforced in the WHERE clause so two concurrent purchases can't both
    /// succeed at a 150-star balance. Returns 1 on success, 0 if the user
    /// either lacked the stars OR was at the freeze cap.
    @Modifying
    @Query(value = """
            UPDATE users
               SET stars = stars - :cost,
                   streak_freezes = streak_freezes + 1
             WHERE id = :userId
               AND stars >= :cost
               AND streak_freezes < :cap
            """, nativeQuery = true)
    int buyStreakFreeze(@Param("userId") String userId,
                        @Param("cost") int cost,
                        @Param("cap") int cap);

    /// Atomic conditional spend of stars for any shop purchase that does
    /// not also need to bump another counter. Returns 1 on success, 0 if
    /// the balance was insufficient — that signals the caller to throw a
    /// retryable "Not enough stars" error.
    @Modifying
    @Query(value = """
            UPDATE users
               SET stars = stars - :cost
             WHERE id = :userId
               AND stars >= :cost
            """, nativeQuery = true)
    int spendStars(@Param("userId") String userId,
                   @Param("cost") int cost);

    /// Bumps streak_freezes by {@code count} but never past {@code cap}.
    /// Used for the L20 unlock to grant the stack-of-5 in one atomic UPDATE
    /// rather than read-modify-write. Idempotent under retry — clamping
    /// at the cap means a duplicate firing is a no-op once the kid is full.
    @Modifying
    @Query(value = """
            UPDATE users
               SET streak_freezes = LEAST(streak_freezes + :count, :cap)
             WHERE id = :userId
            """, nativeQuery = true)
    int grantFreezesUpTo(@Param("userId") String userId,
                         @Param("count") int count,
                         @Param("cap") int cap);

    /// Atomic streak-earn: credits one freeze, bounded by the level-derived
    /// cap. Returns 1 on success, 0 if the user row vanished.
    @Modifying
    @Query(value = """
            UPDATE users
               SET streak_freezes = LEAST(streak_freezes + 1, :cap)
             WHERE id = :userId
            """, nativeQuery = true)
    int earnStreakFreeze(@Param("userId") String userId,
                        @Param("cap") int cap);

    /// Atomic streak-consume: decrements one freeze but never below 0.
    /// Returns 1 when a freeze was consumed, 0 if already at zero.
    @Modifying
    @Query(value = """
            UPDATE users
               SET streak_freezes = streak_freezes - 1
             WHERE id = :userId
               AND streak_freezes > 0
            """, nativeQuery = true)
    int consumeStreakFreeze(@Param("userId") String userId);
}
