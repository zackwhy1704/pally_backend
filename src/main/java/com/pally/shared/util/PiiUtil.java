package com.pally.shared.util;

/**
 * Helpers for the rare case logging needs to reference a sensitive
 * value. Default: don't log PII at all (email, child name, PIN, tokens,
 * Stripe IDs). When you must — and you usually mustn't — use these so
 * the value is masked before it hits the log line.
 */
public final class PiiUtil {
    private PiiUtil() {}

    /// Mask "alice@example.com" → "a***@e***.com" so the log is debuggable
    /// (which mailbox roughly) without leaking the address.
    public static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 0 || at == email.length() - 1) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String host = dot > 0 ? domain.substring(0, dot) : domain;
        String tld = dot > 0 ? domain.substring(dot) : "";
        return mask(local) + "@" + mask(host) + tld;
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty()) return "***";
        if (s.length() == 1) return s + "***";
        return s.charAt(0) + "***";
    }
}
