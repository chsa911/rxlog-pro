// services/bookservice-java-spring/src/main/java/com/rxlog/register/api/RegisterBookResponse.java
package com.rxlog.register.api;

/** Antwort auf /api/register/book. */
public record RegisterBookResponse(String bookId, String barcode, String readingStatus) {}
