package com.pally.infrastructure.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmailVerificationTokenJpaRepository
        extends JpaRepository<EmailVerificationTokenJpaEntity, String> {

    List<EmailVerificationTokenJpaEntity> findByUserId(String userId);
}
