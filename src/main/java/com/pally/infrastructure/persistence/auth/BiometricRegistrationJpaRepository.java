package com.pally.infrastructure.persistence.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface BiometricRegistrationJpaRepository extends JpaRepository<BiometricRegistrationJpaEntity, String> {
    Optional<BiometricRegistrationJpaEntity> findByUserIdAndDeviceIdAndActiveTrue(String userId, String deviceId);
    Optional<BiometricRegistrationJpaEntity> findByUserIdAndDeviceId(String userId, String deviceId);
}
