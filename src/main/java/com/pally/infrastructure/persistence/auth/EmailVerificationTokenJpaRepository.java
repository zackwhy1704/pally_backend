package com.pally.infrastructure.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailVerificationTokenJpaRepository
        extends JpaRepository<EmailVerificationTokenJpaEntity, String> {
}
