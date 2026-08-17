package com.pally.infrastructure.persistence.classroom;

import com.pally.domain.classroom.ClassroomSession;
import com.pally.domain.classroom.ClassroomSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ClassroomSessionRepositoryAdapter implements ClassroomSessionRepository {

    private final ClassroomSessionJpaRepository jpa;

    @Override
    @Transactional(readOnly = true)
    public Optional<ClassroomSession> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClassroomSession> findLiveByJoinCode(String joinCode) {
        return jpa.findFirstByJoinCodeAndStatusNot(joinCode, ClassroomSession.STATUS_ENDED)
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public ClassroomSession save(ClassroomSession session) {
        ClassroomSessionJpaEntity e = new ClassroomSessionJpaEntity();
        e.setId(session.id());
        e.setClassId(session.classId());
        e.setTeacherId(session.teacherId());
        e.setAvatarId(session.avatarId());
        e.setJoinCode(session.joinCode());
        e.setTopicSlug(session.topicSlug());
        e.setQuestionPoolJson(session.questionPoolJson());
        e.setCurrentIndex(session.currentIndex());
        e.setHpRemaining(session.hpRemaining());
        e.setHpMax(session.hpMax());
        e.setDefeated(session.defeated());
        e.setStatus(session.status());
        e.setCreatedAt(session.createdAt());
        e.setStartedAt(session.startedAt());
        e.setEndedAt(session.endedAt());
        jpa.save(e);
        return session;
    }

    private ClassroomSession toDomain(ClassroomSessionJpaEntity e) {
        return new ClassroomSession(e.getId(), e.getClassId(), e.getTeacherId(), e.getAvatarId(),
                e.getJoinCode(), e.getTopicSlug(), e.getQuestionPoolJson(), e.getCurrentIndex(),
                e.getHpRemaining(), e.getHpMax(), e.isDefeated(), e.getStatus(), e.getCreatedAt(),
                e.getStartedAt(), e.getEndedAt());
    }
}
