package com.rxlog.register.api;

public record ReadingStatusResponse(
    String bookId, String status, boolean topBook, String releasedBarcode) {}
