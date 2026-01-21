package com.rxlog.register.web;

import java.util.List;

/**
 * Suggestions payload for the monitoring UI.
 *
 * - If {@code pages} are present: {@code samePagesBooks} contains books with the same page count.
 * - If {@code barcode} is present: {@code confusableBarcodeSuggestions} contains configured
 *   barcode alternatives.
 */
public record MobileIssueSuggestionsResponse(
        String issueId,
        String errorCode,
        String barcode,
        Integer pages,
        Integer samePagesCount,
        List<BookHint> samePagesBooks,
        List<BarcodeGroup> confusableBarcodeSuggestions) {

    public record BookHint(
            String bookId,
            String author,
            String titleKeyword,
            Integer pages,
            List<String> barcodes) {}

    /** A suggested barcode plus the books currently attached to it. */
    public record BarcodeGroup(String barcode, List<BookHint> books) {}
}
