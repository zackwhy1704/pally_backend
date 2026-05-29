package com.pally.infrastructure.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Per-cache TTLs (no global TTL — each name has different staleness
 * tolerance). Caffeine today; Stage 2 swaps to RedisCacheManager via
 * config (same cache names + TTLs) without touching business code.
 *
 * <p>TTLs are conservative — short enough that a missed eviction can't
 * outlive a state change for long (e.g. a Stripe cancellation), long
 * enough to absorb hot reads.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String ENTITLEMENT = "entitlement";
    public static final String CURRICULUM = "curriculum";
    public static final String COVERAGE = "coverage";

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                cache(ENTITLEMENT, Duration.ofSeconds(60), 10_000),
                cache(CURRICULUM, Duration.ofHours(1), 1_000),
                cache(COVERAGE, Duration.ofMinutes(5), 10_000)));
        return manager;
    }

    private CaffeineCache cache(String name, Duration ttl, int maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
