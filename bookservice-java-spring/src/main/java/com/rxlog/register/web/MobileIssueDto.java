package com.rxlog.register.web;

/** Issue row for the monitoring UI. */
public record MobileIssueDto(
        String id,
        String clientChangeId,
        String barcode,
        Integer pages,

        String readingStatus,
        String readingStatusChangedAt,

        Boolean topBook,
        String topBookSetAt,

        String errorCode,
        String status,
        String createdAt,
        String message) {}
