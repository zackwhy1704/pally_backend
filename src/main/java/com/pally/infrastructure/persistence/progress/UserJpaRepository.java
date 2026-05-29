package com.pally.infrastructure.persistence.progress;

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

    Optional<UserJpaEntity> findByLinkCode(String linkCode);

    List<UserJpaEntity> findByParentId(String parentId);

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
    @Modifying
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
}
