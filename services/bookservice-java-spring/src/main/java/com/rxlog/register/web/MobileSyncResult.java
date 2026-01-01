package com.rxlog.register.web;

public record MobileSyncResult(
        String clientChangeId,
        String status,   // applied | needs_review | rejected
        String bookId,   // uuid as text
        String issueId,  // uuid as text
        String errorCode
) {}