package com.pally.domain.module;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.port.StoragePort;
import com.pally.domain.module.port.TtsPort;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleNarrationJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleNarrationJpaRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Generates TTS narration for a learning module's LEARN items.
 *
 * <p>Narration is generated asynchronously: the caller gets a narration ID
 * back immediately (HTTP 202), and the service processes TTS in the background.
 * The client polls the GET endpoint until status = READY.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NarrationService {

    private static final int WORDS_PER_MINUTE = 150;

    private final TtsPort ttsPort;
    private final StoragePort storagePort;
    private final ModuleNarrationJpaRepository narrationRepo;
    private final ModuleContentItemJpaRepository itemRepo;
    private final ObjectMapper objectMapper;

    private final ExecutorService ttsPool = Executors.newFixedThreadPool(2);

    /**
     * Starts async narration generation. Returns the narration ID immediately.
     * If narration already exists for this module, returns the existing one.
     *
     * @param moduleId the learning module to narrate
     * @param voiceId  the ElevenLabs voice ID (or "default")
     * @return narration ID
     */
    public String generateAsync(String moduleId, String voiceId) {
        Optional<ModuleNarrationJpaEntity> existing = narrationRepo.findByModuleId(moduleId);
        if (existing.isPresent()) {
            String status = existing.get().getStatus();
            if ("READY".equals(status) || "GENERATING".equals(status)) {
                log.info("[Narration] Already exists for module={} status={}", moduleId, status);
                return existing.get().getId();
            }
            // FAILED — delete and retry
            narrationRepo.delete(existing.get());
        }

        String narrationId = IdGenerator.newId();
        String resolvedVoice = (voiceId != null && !voiceId.isBlank()) ? voiceId : "default";

        ModuleNarrationJpaEntity narration = new ModuleNarrationJpaEntity();
        narration.setId(narrationId);
        narration.setModuleId(moduleId);
        narration.setVoiceId(resolvedVoice);
        narration.setSegmentsJson("[]");
        narration.setTotalDurationMs(0);
        narration.setStatus("GENERATING");
        narration.setCreatedAt(Instant.now());
        narrationRepo.save(narration);

        log.info("[Narration] Queued generation id={} module={} voice={}",
                narrationId, moduleId, resolvedVoice);

        ttsPool.submit(() -> generateInBackground(narrationId, moduleId, resolvedVoice));

        return narrationId;
    }

    /**
     * Returns the narration entity for a module, if it exists.
     */
    @Transactional(readOnly = true)
    public Optional<ModuleNarrationJpaEntity> get(String moduleId) {
        return narrationRepo.findByModuleId(moduleId);
    }

    // ── Background processing ───────────────────────────────────────────

    private void generateInBackground(String narrationId, String moduleId, String voiceId) {
        try {
            List<ModuleContentItemJpaEntity> learnItems =
                    itemRepo.findByModuleIdAndStageOrderBySortOrder(moduleId, ModuleStage.LEARN.name());

            if (learnItems.isEmpty()) {
                log.warn("[Narration] No LEARN items for module={}, marking FAILED", moduleId);
                updateStatus(narrationId, "FAILED", "[]", 0);
                return;
            }

            List<Map<String, Object>> segments = new ArrayList<>();
            int totalDurationMs = 0;

            for (int i = 0; i < learnItems.size(); i++) {
                ModuleContentItemJpaEntity item = learnItems.get(i);
                String narrationText = extractNarrationHint(item.getContentJson());

                if (narrationText == null || narrationText.isBlank()) {
                    log.warn("[Narration] Skipping card {} — no narration_hint", i);
                    continue;
                }

                // Synthesize audio
                byte[] audio = ttsPort.synthesize(narrationText, voiceId);

                // Store the MP3
                String storageKey = "narration/" + moduleId + "/card_" + i + ".mp3";
                String audioUrl = storagePort.upload(storageKey, audio, "audio/mpeg");

                // Estimate duration from word count (~150 words/minute)
                int wordCount = narrationText.split("\\s+").length;
                int durationMs = (int) ((wordCount / (double) WORDS_PER_MINUTE) * 60_000);
                durationMs = Math.max(durationMs, 1000); // minimum 1 second

                Map<String, Object> segment = new LinkedHashMap<>();
                segment.put("cardIndex", i);
                segment.put("scriptText", narrationText);
                segment.put("audioUrl", audioUrl);
                segment.put("durationMs", durationMs);
                segments.add(segment);

                totalDurationMs += durationMs;
            }

            String segmentsJson = objectMapper.writeValueAsString(segments);
            updateStatus(narrationId, "READY", segmentsJson, totalDurationMs);

            log.info("[Narration] READY id={} module={} segments={} totalMs={}",
                    narrationId, moduleId, segments.size(), totalDurationMs);

        } catch (Exception e) {
            log.error("[Narration] FAILED id={} module={}: {}",
                    narrationId, moduleId, e.getMessage(), e);
            updateStatus(narrationId, "FAILED", "[]", 0);
        }
    }

    @Transactional
    protected void updateStatus(String narrationId, String status, String segmentsJson, int totalDurationMs) {
        narrationRepo.findById(narrationId).ifPresent(n -> {
            n.setStatus(status);
            n.setSegmentsJson(segmentsJson);
            n.setTotalDurationMs(totalDurationMs);
            narrationRepo.save(n);
        });
    }

    String extractNarrationHint(String contentJson) {
        try {
            Map<String, Object> content = objectMapper.readValue(contentJson,
                    new TypeReference<>() {});
            Object hint = content.get("narration_hint");
            return hint != null ? hint.toString() : null;
        } catch (Exception e) {
            log.warn("[Narration] Failed to parse content JSON for narration_hint", e);
            return null;
        }
    }
}
