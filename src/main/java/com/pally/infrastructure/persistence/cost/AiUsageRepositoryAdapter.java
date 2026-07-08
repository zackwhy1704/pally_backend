package com.pally.infrastructure.persistence.cost;

import com.pally.domain.cost.AiCallType;
import com.pally.domain.cost.AiUsage;
import com.pally.domain.cost.AiUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AiUsageRepositoryAdapter implements AiUsageRepository {

    private final AiUsageJpaRepository jpa;

    @Override
    @Transactional
    public AiUsage save(AiUsage u) {
        AiUsageJpaEntity e = new AiUsageJpaEntity();
        e.setId(u.id());
        e.setUserId(u.userId());
        e.setAvatarId(u.avatarId());
        e.setCallType(u.callType());
        e.setPurposeLabel(u.purposeLabel());
        e.setTrigger(u.trigger());
        e.setModel(u.model());
        e.setInputTokens(u.inputTokens());
        e.setOutputTokens(u.outputTokens());
        e.setEstCostMicros(u.estCostMicros());
        e.setSuccess(u.success());
        e.setEstimated(u.estimated());
        e.setCreatedAt(u.createdAt());
        jpa.save(e);
        return u;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostRow> summarize(Instant from, Instant to) {
        return jpa.summarizeRaw(from, to).stream()
                .map(r -> new CostRow(
                        (String) r[0],
                        (AiCallType) r[1],
                        ((Number) r[2]).longValue(),
                        ((Number) r[3]).longValue()))
                .toList();
    }
}
