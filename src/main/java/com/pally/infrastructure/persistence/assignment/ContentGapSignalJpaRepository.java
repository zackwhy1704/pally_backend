package com.pally.infrastructure.persistence.assignment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContentGapSignalJpaRepository extends JpaRepository<ContentGapSignalJpaEntity, String> {

    List<ContentGapSignalJpaEntity> findByClassId(String classId);

    /// ACCOUNT DELETION Phase 1 SURVIVOR (anonymize-in-place): the content-gap signal
    /// stays as the teacher's operational record but is stripped of the student
    /// identity. No FK, so the row otherwise retains the dead user_id.
    @Modifying
    @Query("UPDATE ContentGapSignalJpaEntity c SET c.userId = null WHERE c.userId = :userId")
    int anonymizeByUserId(@Param("userId") String userId);
}
