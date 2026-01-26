package com.rxlog.register.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request-Payload for the finale registration of a book. */
public record RegisterBookRequest(
        // basic data
        @NotBlank String author,
        @NotBlank String publisher,
        @NotNull @Min(1) Integer pages,

        // title-keywords + positions
        @NotBlank String titleKeyword,
        @NotNull @Min(1) Integer titleKeywordPosition,
        String titleKeyword2,
        Integer titleKeyword2Position,
        String titleKeyword3,
        Integer titleKeyword3Position,
        @NotNull Integer width,
        @NotNull Integer height,

        // reading-status
        @NotBlank String readingStatus, // "in_progress" | "finished" | "abandoned"
        Boolean topBook,

        // optional classification
        // true  = Fiction
        // false = Non-Fiction
        // null  = not specified
        Boolean isFiction,
        String genre,
        String subGenre,
        String themes,


        // provided barcode (e.g. "ogk001")
        @NotBlank String barcode) {}
