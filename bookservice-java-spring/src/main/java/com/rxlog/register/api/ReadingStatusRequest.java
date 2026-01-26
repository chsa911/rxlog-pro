package com.rxlog.register.api;

/**
 * DTO for updating reading status. - bookId: UUID string of the book - status: "in_progress" |
 * "finished" | "abandoned"
 */
public record ReadingStatusRequest(String bookId, String status, Boolean topBook) {}
