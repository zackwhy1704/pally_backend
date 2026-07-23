package com.pally.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CacheInvalidationServiceTest {

    private final CacheInvalidationService service = new CacheInvalidationService();

    @Test
    void onWikiContentChanged_noActiveKeepalive_doesNotStartOne() {
        // Fail-without-fix: pre-fix this was an unconditional stop()+start(), so EVERY compile
        // — including one on a chat-less avatar a teacher never opens — spun up a 4-minute ping
        // loop nothing stopped until redeploy. A compile must not warm a cache no one is reading.
        CacheKeepAliveService keepalive = mock(CacheKeepAliveService.class);
        when(keepalive.isActive("av1")).thenReturn(false);

        service.onWikiContentChanged("av1", keepalive);

        verify(keepalive, never()).startKeepalive(anyString());
        verify(keepalive, never()).stopKeepalive(anyString());
    }

    @Test
    void onWikiContentChanged_activeKeepalive_refreshesInStopThenStartOrder() {
        // A LIVE session (someone is chatting) whose wiki just changed → refresh so the next
        // ping re-warms the new content. This is the one case where restart is correct.
        CacheKeepAliveService keepalive = mock(CacheKeepAliveService.class);
        when(keepalive.isActive("av1")).thenReturn(true);

        service.onWikiContentChanged("av1", keepalive);

        InOrder inOrder = inOrder(keepalive);
        inOrder.verify(keepalive).stopKeepalive("av1");
        inOrder.verify(keepalive).startKeepalive("av1");
    }
}
