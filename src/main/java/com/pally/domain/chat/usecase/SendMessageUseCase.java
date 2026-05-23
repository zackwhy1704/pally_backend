package com.pally.domain.chat.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.chat.ChatStreamEvent;
import com.pally.domain.chat.port.ChatPort;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Use case: send a message to an avatar and receive a streaming response.
 *
 * <p>Injects wiki context into the system prompt before streaming to Claude.</p>
 */
@Service
@RequiredArgsConstructor
public class SendMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendMessageUseCase.class);
    private static final int HISTORY_LIMIT = 20;
    private static final int MAX_WIKI_CHARS = 8000;

    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final ChatRepository chatRepository;
    private final ChatPort chatProxy;

    public record StreamEvent(String type, String payload) {}

    public Flux<StreamEvent> executeStream(String avatarId, String userId, String userMessage) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        // Persist user message
        ChatMessage userMsg = ChatMessage.create(avatarId, userId, ChatMessage.Role.USER, userMessage, null);
        chatRepository.save(userMsg);

        // Build wiki context
        List<WikiPage> wikiPages = wikiRepository.findByAvatarId(avatarId);
        String wikiContext = buildWikiContext(wikiPages);

        // Fetch recent history for context window
        List<ChatMessage> history = chatRepository.findByAvatarId(avatarId, HISTORY_LIMIT);

        String systemPrompt = buildSystemPrompt(avatar, wikiContext);

        log.debug("Streaming chat avatarId={} historySize={} wikiChars={}", avatarId, history.size(), wikiContext.length());

        // Buffer the assistant reply for persistence
        StringBuilder replyBuffer = new StringBuilder();

        return chatProxy.streamChat(systemPrompt, history, userMessage)
                .doOnNext(event -> {
                    if (event instanceof ChatStreamEvent.Token token) {
                        replyBuffer.append(token.text());
                    }
                })
                .doOnComplete(() -> {
                    if (!replyBuffer.isEmpty()) {
                        ChatMessage assistantMsg = ChatMessage.create(
                                avatarId, userId, ChatMessage.Role.ASSISTANT,
                                replyBuffer.toString(), null
                        );
                        chatRepository.save(assistantMsg);
                        log.debug("Saved assistant reply for avatarId={} chars={}", avatarId, replyBuffer.length());
                    }
                })
                .map(event -> switch (event) {
                    case ChatStreamEvent.Token t -> new StreamEvent("delta", t.text());
                    case ChatStreamEvent.Done d  -> new StreamEvent("done", d.sourceFile() != null ? d.sourceFile() : "");
                    case ChatStreamEvent.Error e -> new StreamEvent("error", e.message());
                });
    }

    private String buildSystemPrompt(Avatar avatar, String wikiContext) {
        String teachingStyle = switch (avatar.getPedagogyMode()) {
            case SOCRATIC -> """
                    Use the Socratic method: guide the child with questions rather than giving answers directly.
                    Ask "What do you think happens if...?" or "Can you guess why...?" before explaining.
                    Praise effort and thinking process, not just correct answers.
                    """;
            case DIRECT -> """
                    Teach directly and clearly. Give concise explanations with step-by-step breakdowns.
                    Use concrete examples the child can follow immediately.
                    Confirm understanding with a short check question at the end of each explanation.
                    """;
        };
        String gradeCtx = avatar.getGradeLevel() != null
                ? "The child is in grade " + avatar.getGradeLevel() + ". " : "";
        String curriculumCtx = avatar.getCurriculumType() != null
                ? "Curriculum: " + avatar.getCurriculumType() + ". " : "";

        return """
                You are %s, a friendly AI tutor specialising in %s for children aged 8-14.
                %s%sAlways be encouraging, patient, and age-appropriate.
                Use simple language, analogies, and examples kids love: food, games, sports, animals.
                ONLY answer questions about %s. For off-topic questions, kindly redirect.

                %s
                ## Knowledge Base
                %s

                When you reference the knowledge base, end your reply with:
                SOURCE: [page-slug]
                """.formatted(avatar.getName(), avatar.getSubject().name(),
                gradeCtx, curriculumCtx,
                avatar.getSubject().name(),
                teachingStyle,
                wikiContext);
    }

    private String buildWikiContext(List<WikiPage> pages) {
        if (pages.isEmpty()) return "No knowledge has been loaded yet.";
        String combined = pages.stream()
                .map(p -> "### " + p.getTitle() + "\n" + p.getContent())
                .collect(Collectors.joining("\n\n"));
        return combined.length() > MAX_WIKI_CHARS
                ? combined.substring(0, MAX_WIKI_CHARS) + "\n... [truncated]"
                : combined;
    }
}
