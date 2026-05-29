package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CentreEnrollCodeJpaRepository
        extends JpaRepository<CentreEnrollCodeJpaEntity, String> {

    List<CentreEnrollCodeJpaEntity> findByOrganizationIdOrderByCreatedAtDesc(
            String organizationId);

    @Modifying
    @Query(value = "UPDATE centre_enroll_codes SET uses = uses + 1 "
            + "WHERE code = :code AND uses < max_uses",
            nativeQuery = true)
    int incrementUses(@Param("code") String code);
}
