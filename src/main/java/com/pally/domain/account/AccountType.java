package com.pally.domain.account;

/**
 * Family account role for a user.
 *
 * <ul>
 *   <li>{@link #SOLO}   — legacy/default; an unlinked individual account.</li>
 *   <li>{@link #CHILD}  — linked to a parent via {@code parentId}.</li>
 *   <li>{@link #PARENT} — manages one or more linked children.</li>
 *   <li>{@link #ADULT}  — a web centre-admin (B2B). The web signup is adults-only and
 *       never a student data subject, so ADULT accounts are AGE-EXEMPT (like PARENT):
 *       no birth year required, never treated as under-13.</li>
 * </ul>
 *
 * Persisted as {@code @Enumerated(EnumType.STRING)} so the DB column keeps the
 * exact names and JSON serialises to the same strings the clients already expect.
 */
public enum AccountType {
    SOLO,
    CHILD,
    PARENT,
    ADULT
}
