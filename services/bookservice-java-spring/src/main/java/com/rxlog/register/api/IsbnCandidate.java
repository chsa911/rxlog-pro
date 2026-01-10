package com.rxlog.register.api;

public record IsbnCandidate(
        String isbn13,
        String title,
        String author,
        String publisher
) {}