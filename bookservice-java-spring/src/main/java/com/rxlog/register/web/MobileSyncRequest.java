package com.rxlog.register.web;

import java.util.List;

public record MobileSyncRequest(List<MobileChange> changes) {

    public record MobileChange(
            String clientChangeId,
            String barcode,
            Integer pages,

            String readingStatus,          // in_progress | finished | abandoned
            String readingStatusChangedAt, // ISO-8601

            Boolean topBook,               // optional
            String topBookSetAt            // ISO-8601 (required if topBook == true)
    ) {}
}