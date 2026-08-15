package com.pally.domain.classroom.dto;

public record ClassroomAttackRequest(String participantToken, String questionId, Integer selectedIndex) {}
