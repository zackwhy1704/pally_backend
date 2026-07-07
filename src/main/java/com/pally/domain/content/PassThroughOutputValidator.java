package com.pally.domain.content;

import org.springframework.stereotype.Component;

import java.util.List;

/// Phase 1 implementation of the OutputValidator seam: validates NOTHING and returns every
/// output unchanged, so behaviour is identical to before the seam existed (the seam is
/// structure-only in Phase 1). Phase 3 replaces this bean with real per-OutputType rules.
@Component
public class PassThroughOutputValidator implements OutputValidator {

    @Override
    public <T> List<T> retainValid(List<T> outputs, OutputType type) {
        return outputs;
    }
}
