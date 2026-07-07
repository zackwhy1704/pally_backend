package com.pally.domain.content;

import java.util.List;

/// Shared VALIDATION capability — one of the three composable content-pipeline seams
/// (ContextAssembler, ContentLlmClient, OutputValidator). Every surface that produces
/// content routes its output through this at the persist boundary, so "is this output
/// usable?" is decided in ONE place instead of leaf-by-leaf.
///
/// Phase 1 ships this seam as a PASS-THROUGH (see PassThroughOutputValidator) so output is
/// unchanged. Phase 3 swaps in real per-OutputType rules — a blank SPOT_MISTAKE, a
/// raw-source-fragment CHALLENGE, a truncated report are dropped/regenerated, never persisted.
/// Because the seam is already wired everywhere output is persisted, Phase 3 is a one-place
/// change rather than five separate fixes that can each be missed.
///
/// NOTE (Phase 3, deliberate): the real validator's coverage must be derived from a STRUCTURAL
/// property (every OutputType / every generation site), not a hand-enumerated list — an
/// enumerated guard inherits the author's blind spots (the af26659 draft-twin miss).
public interface OutputValidator {

    /// Return only the outputs that are valid for their type. Pass-through returns every
    /// output (Phase 1); Phase 3 omits malformed ones.
    <T> List<T> retainValid(List<T> outputs, OutputType type);
}
