package com.pally.domain.boss.dto;

/**
 * Result of one attack. {@code hitLanded} is the ONLY thing the client needs to
 * decide which animation to play (hit vs. cosmetic counterattack) — the actual
 * HP/defeated truth is in {@code state}, not derived client-side.
 */
public record BossAttackResponse(BossStateResponse state, boolean hitLanded) {}
