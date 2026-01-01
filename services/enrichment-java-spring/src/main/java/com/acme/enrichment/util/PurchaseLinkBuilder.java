package com.acme.enrichment.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;

public final class PurchaseLinkBuilder {
    private PurchaseLinkBuilder() {}

    public static String eurobuchUrl(String base, String isbn13, String author, String titleSnippet, String publisher) {
        if (isbn13 != null && !isbn13.isBlank()) {
            return base + "&isbn=" + enc(isbn13);
        }
        StringJoiner sj = new StringJoiner(" ");
        addIfPresent(sj, author);
        addIfPresent(sj, titleSnippet);
        addIfPresent(sj, publisher);
        return base + "&search=" + enc(sj.toString().trim());
    }

    private static void addIfPresent(StringJoiner sj, String v) {
        if (v != null && !v.isBlank()) sj.add(v.trim());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}