package com.pally.infrastructure.persistence.quiz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.quiz.DuplicateSubmissionException;
import com.pally.domain.quiz.QuizIdempotencyRepository;
import com.pally.domain.quiz.QuizResult;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuizIdempotencyRepositoryAdapter implements QuizIdempotencyRepository {

    private final QuizSubmissionIdempotencyJpaRepository repo;
    private final ObjectMapper objectMapper;

    @Override
    public void claim(String userId, String idempotencyKey) {
        QuizSubmissionIdempotencyJpaEntity row = new QuizSubmissionIdempotencyJpaEntity();
        row.setId(IdGenerator.newId());
        row.setUserId(userId);
        row.setIdempotencyKey(idempotencyKey);
        row.setResultJson(null); // set on completion
        row.setCreatedAt(Instant.now());
        try {
            // saveAndFlush so the unique-constraint check happens NOW (a concurrent
            // claim blocks on the index until the winner commits, then fails here).
            repo.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSubmissionException(userId, idempotencyKey);
        }
    }

    @Override
    public void storeResult(String userId, String idempotencyKey, QuizResult result) {
        repo.findByUserIdAndIdempotencyKey(userId, idempotencyKey).ifPresent(row -> {
            try {
                row.setResultJson(objectMapper.writeValueAsString(result));
                repo.save(row);
            } catch (Exception e) {
                // Best-effort: a serialize failure leaves result_json NULL. A later
                // replay then re-grades (safe) rather than returning a broken result.
                log.warn("[QuizIdem] could not store result for user={} key={}: {}",
                        userId, idempotencyKey, e.getMessage());
            }
        });
    }

    @Override
    public Optional<QuizResult> findResult(String userId, String idempotencyKey) {
        return repo.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(QuizSubmissionIdempotencyJpaEntity::getResultJson)
                .filter(json -> json != null && !json.isBlank())
                .flatMap(json -> {
                    try {
                        return Optional.of(objectMapper.readValue(json, QuizResult.class));
                    } catch (Exception e) {
                        log.warn("[QuizIdem] could not read stored result for user={} key={}: {}",
                                userId, idempotencyKey, e.getMessage());
                        return Optional.empty();
                    }
                });
    }
}
