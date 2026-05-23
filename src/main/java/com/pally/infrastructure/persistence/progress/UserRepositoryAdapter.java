package com.pally.infrastructure.persistence.progress;

import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpa;

    @Override
    public Optional<UserStats> findById(String userId) {
        return jpa.findById(userId).map(UserJpaEntity::toDomain);
    }

    @Override
    public UserStats save(UserStats stats) {
        return jpa.save(UserJpaEntity.fromDomain(stats)).toDomain();
    }

    @Override
    public void ensureUserExists(String userId) {
        if (!jpa.existsById(userId)) {
            jpa.save(UserJpaEntity.newUser(userId));
        }
    }
}
