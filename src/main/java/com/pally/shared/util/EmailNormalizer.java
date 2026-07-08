package com.pally.shared.util;

import java.util.Locale;

/**
 * The ONE canonical form for an email used as an account key / uniqueness key.
 * Auth lookups and storage were case-sensitive and inconsistent (register/login
 * stored raw, some sites lowercased) — so {@code User@X.com} and {@code user@x.com}
 * could become two accounts, or a normalized lookup could miss a raw-stored row.
 * Everything that matches, stores, or looks up an email by identity goes through here.
 */
public final class EmailNormalizer {
    private EmailNormalizer() {}

    /** Trimmed + lowercased canonical key. Null in → null out (presence is validated
     *  separately by the DTO layer). */
    public static String canonical(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
