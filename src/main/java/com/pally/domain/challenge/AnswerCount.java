package com.pally.domain.challenge;

/** Answer distribution row: an answer string and how many students gave it. */
public record AnswerCount(String answer, long count) {
}
