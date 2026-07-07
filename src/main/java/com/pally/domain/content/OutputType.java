package com.pally.domain.content;

/// The kind of generated content being validated. Lets ONE OutputValidator seam serve every
/// surface — module items, class reports, marking references — with per-type rules (added in
/// Phase 3; the seam is pass-through in Phase 1).
public enum OutputType {
    MODULE_ITEM,
    REPORT,
    MARKING_REFERENCE
}
