// services/bookservice-java-spring/src/main/java/com/rxlog/register/api/CreateDraftResponse.java
package com.rxlog.register.api;

/**
 * Antwort auf /api/register/draft. In dieser Demo speichern wir nichts, geben nur Maße +
 * pseudo-Draft-Id zurück.
 */
public record CreateDraftResponse(String draftId, Integer width, Integer height) {}
