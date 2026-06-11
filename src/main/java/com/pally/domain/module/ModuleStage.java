package com.pally.domain.module;

/**
 * Stages of a learning module, enforced in order.
 * LEARN → TEST → PROVE → COMPLETE.
 */
public enum ModuleStage {
    LEARN,
    TEST,
    PROVE,
    COMPLETE;

    /**
     * Returns the next stage, or null if already COMPLETE.
     */
    public ModuleStage next() {
        return switch (this) {
            case LEARN -> TEST;
            case TEST -> PROVE;
            case PROVE -> COMPLETE;
            case COMPLETE -> null;
        };
    }
}
