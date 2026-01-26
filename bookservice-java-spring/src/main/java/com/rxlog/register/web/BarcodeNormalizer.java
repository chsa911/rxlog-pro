package com.rxlog.register.web;

import java.util.Set;

public final class BarcodeNormalizer {
    private BarcodeNormalizer() {}

    // Allowed NEW position prefixes (no new 'r')
    private static final Set<Character> POS_NEW = Set.of('d', 'l', 'o');

    // Allowed for LOOKUP (legacy includes 'r')
    private static final Set<Character> POS_LOOKUP = Set.of('d', 'l', 'o', 'r');

    private static final char DEFAULT_POS = 'd';

    // -------------------------
    // Legacy API used by BookDao
    // -------------------------

    /** For backwards compatibility: use WRITE normalization (blocks new 'r'). */
    public static String normalizeOrNull(String raw) {
        return normalizeForWriteOrNull(raw);
    }

    /** For backwards compatibility: use WRITE normalization (blocks new 'r'). */
    public static String normalizeOrThrow(String raw) {
        String n = normalizeForWriteOrNull(raw);
        if (n == null) throw new IllegalArgumentException("Invalid or disallowed barcode: " + raw);
        return n;
    }

    // -------------------------
    // New API: lookup vs write
    // -------------------------

    /**
     * Lookup normalization (used for search/sync):
     * - numeric -> unchanged
     * - prefixed codes: [d|l|o|r] + 1..3 letters + digits
     * - unprefixed: 1..3 letters + digits -> prefix DEFAULT_POS
     * - fallback: any alphanumeric legacy -> unchanged
     */
    public static String normalizeForLookupOrNull(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return null;

        // numeric barcode
        if (s.matches("^\\d+$")) return s;

        // prefixed shelf codes (includes legacy r)
        if (s.matches("^[dlro][a-z]{1,3}\\d+$")) {
            char p = s.charAt(0);
            return POS_LOOKUP.contains(p) ? s : null;
        }

        // unprefixed shelf codes -> default d
        if (s.matches("^[a-z]{1,3}\\d+$")) {
            return DEFAULT_POS + s;
        }

        // fallback: allow legacy alphanumeric for lookup
        if (s.matches("^[a-z0-9]+$")) {
            return s;
        }

        return null;
    }

    /**
     * Write normalization (used for inserting/updating barcodes):
     * Same as lookup, but blocks legacy 'r' as a position prefix.
     */
    public static String normalizeForWriteOrNull(String raw) {
        String s = normalizeForLookupOrNull(raw);
        if (s == null) return null;

        // If it begins with a position prefix, it must be d/l/o (not r)
        if (s.matches("^[dlro].*")) {
            char p = s.charAt(0);
            if (POS_LOOKUP.contains(p) && !POS_NEW.contains(p)) {
                return null; // blocks 'r'
            }
        }
        return s;
    }
}