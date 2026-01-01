package com.acme.enrichment.model;

import java.util.List;

public record BookEnrichmentInput(
        String id,                 // <-- MUST be String (UUID)
        String author,
        String publisher,
        String isbn13,
        List<TitleKeyword> titleKeywords
) {
    public record TitleKeyword(int pos, String kw) {}
}