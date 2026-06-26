package com.pally.infrastructure.persistence.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuizAnswerKeyJpaRepository
        extends JpaRepository<QuizAnswerKeyJpaEntity, String> {

    List<QuizAnswerKeyJpaEntity> findByQuestionIdIn(Collection<String> questionIds);
}
