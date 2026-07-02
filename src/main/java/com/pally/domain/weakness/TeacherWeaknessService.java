package com.pally.domain.weakness;

import com.pally.domain.avatar.Subject;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Teacher-facing per-student weakness view for a class — closes the loop for
 * teachers: they see WHERE each of their students is weak (in this class's
 * subject), so they know what to reteach. Owner-only; empty when the pilot is off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeacherWeaknessService {

    /** Cap per student so the teacher view stays scannable. */
    private static final int MAX_AREAS_PER_STUDENT = 5;

    private final OrgClassRepository orgClassRepository;
    private final CentreAccessService centreAccessService;
    private final ClassRosterRepository rosterRepository;
    private final UserRepository userRepository;
    private final WeaknessProfileService weaknessProfileService;

    public Map<String, Object> perStudentWeakness(String teacherUserId, String classId) {
        String orgId = orgClassRepository.findOrganizationIdByClassId(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        centreAccessService.ensureOwner(teacherUserId, orgId); // 403 if not the owner

        Subject subject = parseSubject(
                orgClassRepository.findSubjectByClassId(classId).orElse(null));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", weaknessProfileService.isEnabled());
        out.put("subject", subject == null ? null : subject.name());
        if (subject == null || !weaknessProfileService.isEnabled()) {
            out.put("students", List.of());
            return out;
        }

        List<String> roster = rosterRepository.activeStudentIds(classId);
        Map<String, String> names = userRepository.findAllByIds(roster).stream()
                .collect(Collectors.toMap(User::getId,
                        u -> u.getDisplayName() != null ? u.getDisplayName() : "",
                        (a, b) -> a));

        List<Map<String, Object>> students = roster.stream().map(sid -> {
            List<String> areas = weaknessProfileService.weaknessPagesFor(sid, subject).stream()
                    .map(WikiPage::getTitle)
                    .limit(MAX_AREAS_PER_STUDENT)
                    .toList();
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("studentId", sid);
            dto.put("displayName", names.getOrDefault(sid, ""));
            dto.put("weakAreas", areas);
            return dto;
        }).toList();

        out.put("students", students);
        return out;
    }

    /** Defensive parse of the stored class subject (enum name or label). */
    private static Subject parseSubject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Subject.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            for (Subject s : Subject.values()) {
                if (s.label().equalsIgnoreCase(raw.trim())) return s;
            }
            log.warn("[Weakness] unrecognised class subject '{}'", raw);
            return null;
        }
    }
}
