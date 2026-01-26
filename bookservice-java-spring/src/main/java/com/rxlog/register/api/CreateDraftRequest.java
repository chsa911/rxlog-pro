// services/bookservice-java-spring/src/main/java/com/rxlog/register/api/CreateDraftRequest.java
package com.rxlog.register.api;

import jakarta.validation.constraints.NotNull;

/** Request für das Anlegen eines Drafts mit bereits normalisierten Maßen (mm). */
public record CreateDraftRequest(@NotNull Integer width, @NotNull Integer height) {}
