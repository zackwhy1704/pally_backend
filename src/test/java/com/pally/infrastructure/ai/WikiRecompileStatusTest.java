package com.pally.infrastructure.ai;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.BrainStateService;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.usecase.CompileJobStore;
import com.pally.domain.knowledge.usecase.CompileWikiUseCase;
import com.pally.domain.knowledge.usecase.FailedPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The centre web polls /avatars then reads GET /wiki/compile/status — which serves
 * {@link CompileJobStore#findByAvatarId}. A recompile (the path the web triggers)
 * must publish its per-page failures there, else a partial recompile looks like a
 * full success to the teacher. This proves the recompile→store wiring.
 */
class WikiRecompileStatusTest {

    private final CompileJobStore store = new CompileJobStore();

    private WikiRecompileScheduler scheduler() {
        return new WikiRecompileScheduler(
                mock(ThreadPoolExecutor.class),
                mock(CompileWikiUseCase.class),
                mock(WikiRepository.class),
                mock(BrainStateService.class),
                mock(AvatarRepository.class),
                store);
    }

    @Test
    void recompileWithFailures_publishesFailedPagesAndHonestTotals() {
        var result = new CompileWikiUseCase.CompileResult(
                3, 0, List.of("Newton I", "Newton II", "Friction"),
                "gemini-2.5-flash", 1, 5000,
                List.of(new FailedPage("osmosis", "DataIntegrity: conflict_note")));

        scheduler().recordRecompileStatus("av-1", result);

        CompileJobStore.JobStatus s = store.findByAvatarId("av-1");
        assertThat(s).isNotNull();
        assertThat(s.state()).isEqualTo(CompileJobStore.JobState.DONE);
        assertThat(s.pagesCompiled()).isEqualTo(3);
        assertThat(s.pagesTotal()).isEqualTo(4);              // 3 compiled + 1 failed
        assertThat(s.failedPages()).extracting(FailedPage::slug).containsExactly("osmosis");
    }

    @Test
    void cleanRecompile_publishesEmptyFailures_soAPriorPartialDoesNotLinger() {
        // First a partial, then a clean recompile — the latest (clean) must win.
        scheduler().recordRecompileStatus("av-2", new CompileWikiUseCase.CompileResult(
                2, 0, List.of("A", "B"), "gemini", 1, 100,
                List.of(new FailedPage("bad", "boom"))));
        scheduler().recordRecompileStatus("av-2", new CompileWikiUseCase.CompileResult(
                3, 0, List.of("A", "B", "C"), "gemini", 1, 100, List.of()));

        CompileJobStore.JobStatus s = store.findByAvatarId("av-2");
        assertThat(s.failedPages()).isEmpty();
        assertThat(s.pagesCompiled()).isEqualTo(3);
        assertThat(s.pagesTotal()).isEqualTo(3);
    }
}
