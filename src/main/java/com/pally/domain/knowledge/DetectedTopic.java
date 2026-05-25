package com.pally.domain.knowledge;

/**
 * A wiki topic detected from the user's message by the TopicRouter.
 * The slugKeyword is used to match against wiki page slugs and titles.
 */
public record DetectedTopic(String slugKeyword, double relevanceScore) {}
