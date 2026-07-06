package com.pally.domain.knowledge.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.infrastructure.ai.CacheInvalidationService;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import com.pally.infrastructure.persistence.knowledge.WikiPageSourceJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * executeBounded is the SYNC compile path (small docs ≤50k). When a compile blows the
 * 4-minute cap it must SIGNAL the timed-out future to stop (cancel(true)) before returning
 * the 504 — otherwise the compile runs orphaned, burning Claude calls. (Stacked compiles
 * are separately prevented by the caller's per-avatar single-flight gate,
 * WikiRecompileScheduler.tryBeginExternalCompile / inFlight — not re-tested here.)
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class CompileWikiUseCaseExecuteBoundedTest {

    @Mock AvatarRepository avatarRepository;
    @Mock KnowledgeRepository knowledgeRepository;
    @Mock WikiRepository wikiRepository;
    @Mock WikiCompilerPort wikiCompiler;
    @Mock CacheInvalidationService cacheInvalidationService;
    @Mock CacheKeepAliveService cacheKeepAliveService;
    @Mock WikiPagePersistenceService persistenceService;
    @Mock WikiPageSourceJpaRepository wikiPageSourceRepo;
    @Mock CompileJobStore compileJobStore;
    @Mock ThreadPoolExecutor aiTaskExecutor;

    @InjectMocks CompileWikiUseCase useCase;

    @Test
    void timeout_cancelsTheFuture_andThrows504() throws Exception {
        Future future = mock(Future.class);
        when(aiTaskExecutor.submit(any(Callable.class))).thenReturn(future);
        when(future.get(4, TimeUnit.MINUTES)).thenThrow(new TimeoutException("slow"));

        assertThatThrownBy(() -> useCase.executeBounded("avatar-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(504));

        verify(future).cancel(true); // the orphan is signalled to stop
    }

    @Test
    void rejectedExecution_throws503() {
        when(aiTaskExecutor.submit(any(Callable.class)))
                .thenThrow(new RejectedExecutionException("queue full"));

        assertThatThrownBy(() -> useCase.executeBounded("avatar-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(503));
    }
}
