package com.pally.domain.account;

/**
 * Family account role for a user.
 *
 * <ul>
 *   <li>{@link #SOLO}   — legacy/default; an unlinked individual account.</li>
 *   <li>{@link #CHILD}  — linked to a parent via {@code parentId}.</li>
 *   <li>{@link #PARENT} — manages one or more linked children.</li>
 * </ul>
 *
 * Persisted as {@code @Enumerated(EnumType.STRING)} so the DB column keeps the
 * exact names ("SOLO"/"CHILD"/"PARENT") and JSON serialises to the same strings
 * the mobile app already expects.
 */
public enum AccountType {
    SOLO,
    CHILD,
    PARENT
}
