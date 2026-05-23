package com.pally.shared.util;

import java.util.UUID;

/**
 * Generates UUID v4 identifiers.
 *
 * <p>Java's standard library does not include UUID v7 as of JDK 21,
 * so we use UUID v4 (random) here. Replace with a UUID v7 library
 * (e.g. com.fasterxml.uuid:java-uuid-generator) when time-sortability
 * is required at scale.</p>
 */
public final class IdGenerator {

    private IdGenerator() {}

    /**
     * @return a new random UUID string (36 characters, hyphenated)
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
