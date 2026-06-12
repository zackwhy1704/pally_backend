package com.pally.infrastructure.storage;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Complement of {@link R2ConfiguredCondition}: local storage is the fallback whenever
 * R2 is NOT fully configured. That covers three cases:
 * <ul>
 *   <li>{@code storage.type=local} (explicit, e.g. tests / dev)</li>
 *   <li>{@code storage.type} unset (defaults imply local)</li>
 *   <li>{@code storage.type=s3} but the {@code R2_*} creds are blank (local/CI safety)</li>
 * </ul>
 *
 * <p>This guarantees exactly one {@code StorageService}/{@code StoragePort} bean exists:
 * R2 when configured, local otherwise — so the app never boots with zero storage beans.
 */
public class LocalStorageActiveCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !R2ConfiguredCondition.isR2SelectedAndConfigured(context.getEnvironment());
    }
}
