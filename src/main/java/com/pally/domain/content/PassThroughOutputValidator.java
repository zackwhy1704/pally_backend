package com.pally.domain.content;

import java.util.List;

/// Phase 1 implementation of the OutputValidator seam: validates NOTHING and returns every
/// output unchanged. SUPERSEDED as the wired bean by {@link RulesOutputValidator} (Phase 3) —
/// no longer a {@code @Component}, so it does not compete for injection. Retained as a
/// behaviour-preservation reference + a no-op test double (unit tests construct it directly to
/// isolate a subject from validation).
public class PassThroughOutputValidator implements OutputValidator {

    @Override
    public <T> List<T> retainValid(List<T> outputs, OutputType type) {
        return outputs;
    }
}
