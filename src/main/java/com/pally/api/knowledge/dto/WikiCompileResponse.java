package com.pally.api.knowledge.dto;

/**
 * Response body for a wiki compilation trigger.
 *
 * @param pagesCompiled total number of pages written (created + updated)
 * @param message       human-readable summary of the compilation result
 */
public record WikiCompileResponse(int pagesCompiled, String message) {}
