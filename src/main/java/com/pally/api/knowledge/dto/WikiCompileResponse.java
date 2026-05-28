package com.pally.api.knowledge.dto;

import java.util.List;

/**
 * Response body for a wiki compilation trigger.
 *
 * @param pagesCompiled total number of pages written (created + updated)
 * @param pageTitles    titles of pages produced by this compile call (in order).
 *                      Used by WikiCompiledScreen to render the "you learned …" list.
 * @param message       human-readable summary of the compilation result
 */
public record WikiCompileResponse(
        int pagesCompiled,
        List<String> pageTitles,
        String message
) {}
