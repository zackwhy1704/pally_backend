package com.pally.domain.knowledge.dto;

import java.util.List;

public record WikiCompileResponse(
        int pagesCompiled,
        List<String> pageTitles,
        String message,
        String compiledBy
) {
    public WikiCompileResponse(int pagesCompiled, List<String> pageTitles, String message) {
        this(pagesCompiled, pageTitles, message, null);
    }
}
