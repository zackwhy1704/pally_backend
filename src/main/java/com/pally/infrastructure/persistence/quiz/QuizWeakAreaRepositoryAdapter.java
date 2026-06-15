package com.pally.infrastructure.persistence.quiz;

import com.pally.domain.parent.QuizWeakAreaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class QuizWeakAreaRepositoryAdapter implements QuizWeakAreaRepository {

    private final QuizQuestionResultJpaRepository jpa;

    @Override
    public List<Object[]> findWeakestTopics(String userId, int limit) {
        return jpa.findWeakestTopics(userId, limit);
    }
}
