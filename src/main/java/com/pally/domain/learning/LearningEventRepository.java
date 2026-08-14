package com.pally.domain.learning;

/** Port for persisting learning events. The JPA adapter lives in infrastructure/persistence. */
public interface LearningEventRepository {

    LearningEvent save(LearningEvent event);
}
