package com.pally.domain.centre;

import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.infrastructure.persistence.avatar.AvatarJpaEntity;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.centre.CentreClassJpaEntity;
import com.pally.infrastructure.persistence.centre.CentreClassJpaRepository;
import com.pally.infrastructure.persistence.centre.CentreEnrolmentJpaEntity;
import com.pally.infrastructure.persistence.centre.CentreEnrolmentJpaRepository;
import com.pally.infrastructure.persistence.centre.CentreJpaEntity;
import com.pally.infrastructure.persistence.centre.CentreJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.UpgradeRequiredException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain service for student enrolment into Centre Mochi classes.
 *
 * <p>Joining a class auto-injects a non-deletable Centre Mochi avatar onto
 * the student's home screen. The avatar is hard-locked to the centre's
 * knowledge corpus (closed-book mode).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreEnrolmentService {

    private final CentreClassJpaRepository classRepo;
    private final CentreEnrolmentJpaRepository enrolmentRepo;
    private final CentreJpaRepository centreRepo;
    private final AvatarJpaRepository avatarRepo;

    /**
     * Joins a student into a class identified by {@code joinCode}.
     *
     * <p>Idempotent: if the student is already enrolled in this class, returns
     * the existing enrolment without creating a duplicate.
     *
     * @param userId   the student's user ID
     * @param joinCode the 12-char class join code
     * @return {@link EnrolmentResult} containing enrolmentId, avatarId, and centre branding
     */
    @Transactional
    public EnrolmentResult joinClass(String userId, String joinCode) {
        // 1. Look up class by join_code
        CentreClassJpaEntity cls = classRepo.findByJoinCode(joinCode.trim().toUpperCase())
                .orElseThrow(() -> new BusinessException("Class code not found", 404));

        // 2. Idempotent check — return existing enrolment if already enrolled
        Optional<CentreEnrolmentJpaEntity> existing =
                enrolmentRepo.findByClassIdAndUserId(cls.getId(), userId);
        if (existing.isPresent()) {
            CentreEnrolmentJpaEntity e = existing.get();
            CentreJpaEntity centre = centreRepo.findById(e.getCentreId())
                    .orElseThrow(() -> new BusinessException("Centre not found", 404));
            log.info("[Centre] userId={} already enrolled in classId={} enrolmentId={}",
                    userId, cls.getId(), e.getId());
            return new EnrolmentResult(e.getId(), e.getAvatarId(),
                    centre.getDisplayName(), centre.getAccentColor());
        }

        // 3. Check seat cap
        long activeCount = enrolmentRepo.countActiveByClassId(cls.getId());
        if (activeCount >= cls.getSeatCap()) {
            throw new UpgradeRequiredException("CENTRE_SEAT_CAP");
        }

        // 4. Load centre for branding
        CentreJpaEntity centre = centreRepo.findById(cls.getCentreId())
                .orElseThrow(() -> new BusinessException("Centre not found", 404));

        // 5. Create enrolment row
        String enrolmentId = IdGenerator.newId();
        CentreEnrolmentJpaEntity enrolment = new CentreEnrolmentJpaEntity();
        enrolment.setId(enrolmentId);
        enrolment.setCentreId(cls.getCentreId());
        enrolment.setClassId(cls.getId());
        enrolment.setUserId(userId);
        enrolment.setStatus("ACTIVE");
        enrolment.setJoinedAt(Instant.now());
        enrolmentRepo.save(enrolment);

        // 6. Auto-create Centre Mochi avatar
        String avatarName = cls.getName() + " Mochi";
        Subject subject = resolveSubject(cls.getSubject());
        CharacterType characterType = resolveCharacter(cls.getCharacterType());

        AvatarJpaEntity avatar = new AvatarJpaEntity();
        avatar.setId(IdGenerator.newId());
        avatar.setUserId(userId);
        avatar.setName(avatarName);
        avatar.setSubject(subject);
        avatar.setCharacterType(characterType);
        avatar.setWikiPageCount(0);
        avatar.setCreatedAt(Instant.now());
        avatar.setPedagogyMode(com.pally.domain.avatar.Avatar.PedagogyMode.SOCRATIC);
        avatar.setTeachingMode(com.pally.domain.avatar.TeachingMode.TEACHING);
        avatar.setBrainState("READY");
        avatar.setActive(true);
        avatar.setCentreAvatar(true);
        // centre_enrolment_id column removed in V58 — enrolmentId tracked in centre_enrolments table only
        avatar.setAvatarLocked(false);
        avatarRepo.save(avatar);

        // 7. Update enrolment.avatar_id
        enrolment.setAvatarId(avatar.getId());
        enrolmentRepo.save(enrolment);

        log.info("[Centre] userId={} joined classId={} avatarId={}",
                userId, cls.getId(), avatar.getId());

        return new EnrolmentResult(enrolmentId, avatar.getId(),
                centre.getDisplayName(), centre.getAccentColor());
    }

    /**
     * Marks the enrolment as REMOVED and locks the avatar.
     * History remains readable; no new chats are allowed.
     */
    @Transactional
    public void leaveClass(String userId, String enrolmentId) {
        CentreEnrolmentJpaEntity enrolment = enrolmentRepo.findById(enrolmentId)
                .orElseThrow(() -> new BusinessException("Enrolment not found", 404));
        if (!enrolment.getUserId().equals(userId)) {
            throw new BusinessException("Enrolment not found", 404);
        }
        enrolment.setStatus("REMOVED");
        enrolmentRepo.save(enrolment);

        // Lock the avatar
        if (enrolment.getAvatarId() != null) {
            avatarRepo.findById(enrolment.getAvatarId()).ifPresent(avatar -> {
                avatar.setAvatarLocked(true);
                avatar.setActive(false);
                avatarRepo.save(avatar);
            });
        }

        log.info("[Centre] userId={} left enrolmentId={}", userId, enrolmentId);
    }

    /**
     * Locks all ACTIVE enrolments in a class — called when a centre lapses.
     */
    @Transactional
    public void lockEnrolment(String enrolmentId) {
        enrolmentRepo.findById(enrolmentId).ifPresent(enrolment -> {
            enrolment.setStatus("LAPSED");
            enrolmentRepo.save(enrolment);
            if (enrolment.getAvatarId() != null) {
                avatarRepo.findById(enrolment.getAvatarId()).ifPresent(avatar -> {
                    avatar.setAvatarLocked(true);
                    avatar.setActive(false);
                    avatarRepo.save(avatar);
                });
            }
            log.info("[Centre] enrolmentId={} locked (LAPSED)", enrolmentId);
        });
    }

    // ── private helpers ────────────────────────────────────────────────

    private Subject resolveSubject(String raw) {
        if (raw == null || raw.isBlank()) return Subject.GENERAL;
        try {
            return Subject.valueOf(raw.toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return Subject.GENERAL;
        }
    }

    private CharacterType resolveCharacter(String raw) {
        if (raw == null || raw.isBlank()) return CharacterType.MOCHI;
        try {
            return CharacterType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CharacterType.MOCHI;
        }
    }
}
