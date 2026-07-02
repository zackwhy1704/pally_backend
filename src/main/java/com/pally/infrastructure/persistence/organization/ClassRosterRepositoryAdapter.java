package com.pally.infrastructure.persistence.organization;

import com.pally.domain.weakness.ClassRosterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ClassRosterRepositoryAdapter implements ClassRosterRepository {

    private final ClassMembershipJpaRepository membershipRepo;

    @Override
    public List<String> activeStudentIds(String classId) {
        return membershipRepo.findByClassId(classId).stream()
                .filter(m -> ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus()))
                .filter(m -> ClassMembershipJpaEntity.ROLE_STUDENT.equals(m.getRole()))
                .map(ClassMembershipJpaEntity::getUserId)
                .toList();
    }
}
