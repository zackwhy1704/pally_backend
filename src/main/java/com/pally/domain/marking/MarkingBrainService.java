package com.pally.domain.marking;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read model for the teacher-facing Marking Assistant panel: the COMPILED
 * marking standard (marking-wiki pages) + brain state + per-page conflict flag,
 * so the teacher SEES what the assistant has learned — mirroring how the student
 * brain shows its wiki pages — instead of uploads vanishing into a blob.
 */
@Service
@RequiredArgsConstructor
public class MarkingBrainService {

    private static final int PREVIEW_CHARS = 280;

    private final MarkingCorpusService markingCorpusService;
    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;

    /** Assemble the marking brain view for a class's (orgId, subject) corpus. */
    public Map<String, Object> brainForClass(String classId) {
        Map<String, Object> out = new LinkedHashMap<>();
        String avatarId = markingCorpusService.findAvatarIdForClass(classId).orElse(null);
        if (avatarId == null) {
            // No marking materials compiled yet for this subject.
            out.put("state", "NOT_BUILT");
            out.put("pageCount", 0);
            out.put("pages", List.of());
            out.put("hasConflicts", false);
            return out;
        }

        Avatar avatar = avatarRepository.findById(avatarId).orElse(null);
        List<WikiPage> pages = wikiRepository.findActiveByAvatarId(avatarId);

        List<Map<String, Object>> pageDtos = new ArrayList<>();
        boolean anyConflict = false;
        for (WikiPage p : pages) {
            String body = p.getHumanCorrection() != null && !p.getHumanCorrection().isBlank()
                    ? p.getHumanCorrection() : p.getContent();
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("title", p.getTitle());
            dto.put("slug", p.getSlug());
            dto.put("preview", preview(body));
            dto.put("certainty", p.getCertainty() != null ? p.getCertainty().name() : null);
            dto.put("hasConflict", p.isHasConflict());
            anyConflict = anyConflict || p.isHasConflict();
            pageDtos.add(dto);
        }

        out.put("state", avatar != null ? avatar.getBrainState().name() : "READY");
        out.put("subject", avatar != null ? avatar.getSubject().label() : null);
        out.put("pageCount", pageDtos.size());
        out.put("pages", pageDtos);
        out.put("hasConflicts", anyConflict);
        return out;
    }

    private static String preview(String body) {
        if (body == null) return "";
        String stripped = body.strip();
        return stripped.length() <= PREVIEW_CHARS
                ? stripped : stripped.substring(0, PREVIEW_CHARS) + "…";
    }
}
