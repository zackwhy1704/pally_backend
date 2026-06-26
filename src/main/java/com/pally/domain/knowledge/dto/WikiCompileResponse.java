package com.pally.domain.knowledge.dto;

import com.pally.domain.knowledge.usecase.FailedPage;

import java.util.List;

public record WikiCompileResponse(
        int pagesCompiled,
        List<String> pageTitles,
        String message,
        String compiledBy,
        List<FailedPage> failedPages
) {
    public WikiCompileResponse(int pagesCompiled, List<String> pageTitles, String message) {
        this(pagesCompiled, pageTitles, message, null, List.of());
    }

    public WikiCompileResponse(int pagesCompiled, List<String> pageTitles,
                               String message, String compiledBy) {
        this(pagesCompiled, pageTitles, message, compiledBy, List.of());
    }
}
