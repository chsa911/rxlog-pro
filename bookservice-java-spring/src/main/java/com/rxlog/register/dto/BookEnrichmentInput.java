package com.rxlog.register.dto;

import java.util.List;

public record BookEnrichmentInput(
        String id,
        String author,
        String publisher,
        String isbn13,
        List<TitleKeyword> titleKeywords
) {
    public record TitleKeyword(int pos, String kw) {}
}