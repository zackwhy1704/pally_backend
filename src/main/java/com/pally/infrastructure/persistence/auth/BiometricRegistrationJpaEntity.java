package com.pally.infrastructure.persistence.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "biometric_registrations")
@Getter @Setter @NoArgsConstructor
public class BiometricRegistrationJpaEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", nullable = false) private String userId;
    @Column(name = "device_id", nullable = false) private String deviceId;
    @Column(name = "device_name") private String deviceName;
    @Column(name = "registered_at", nullable = false) private Instant registeredAt;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "is_active", nullable = false) private boolean active;
}
