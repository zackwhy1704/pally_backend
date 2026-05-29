package com.pally.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded executor for heavy Claude-backed jobs (wiki compile, big-doc
 * OCR follow-ups). The web tier can accept thousands of concurrent
 * requests under virtual threads, but each compile pins one OS worker
 * for 30-60s and burns Claude budget — so we cap concurrency here and
 * let the queue absorb bursts.
 *
 * <p>Rejection policy intentionally throws ({@link ThreadPoolExecutor.AbortPolicy})
 * so a flooded queue surfaces a 503 to the client instead of silently
 * back-pressuring the request thread; the client retries.
 */
@Configuration
@EnableAsync
public class AiTaskExecutorConfig {

    public static final String AI_TASK_EXECUTOR = "aiTaskExecutor";

    @Bean(name = AI_TASK_EXECUTOR)
    public ThreadPoolExecutor aiTaskExecutor() {
        int core = Integer.parseInt(System.getenv()
                .getOrDefault("AI_TASK_CORE_THREADS", "2"));
        int max = Integer.parseInt(System.getenv()
                .getOrDefault("AI_TASK_MAX_THREADS", "4"));
        int queueCapacity = Integer.parseInt(System.getenv()
                .getOrDefault("AI_TASK_QUEUE_CAPACITY", "10"));
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                core, max,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread t = new Thread(runnable, "ai-task-" + runnable.hashCode());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(false);
        return pool;
    }
}
