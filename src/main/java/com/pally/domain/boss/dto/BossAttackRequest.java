package com.pally.domain.boss.dto;

/** @param questionId must match the boss's currently-expected question (server-enforced). */
public record BossAttackRequest(String questionId, Integer selectedIndex) {}
