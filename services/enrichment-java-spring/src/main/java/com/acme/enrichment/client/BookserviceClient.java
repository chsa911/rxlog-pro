package com.acme.enrichment.client;

import com.acme.enrichment.model.BookEnrichmentInput;
import com.acme.enrichment.model.BookEnrichmentPatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class BookserviceClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient wc;
    private final String authToken;

    public BookserviceClient(
            @Value("${bookservice.baseUrl}") String baseUrl,
            @Value("${bookservice.authToken:}") String authToken
    ) {
        this.wc = WebClient.builder().baseUrl(baseUrl).build();
        this.authToken = authToken == null ? "" : authToken;
    }

    public BookEnrichmentInput getInput(String bookId) {
        return wc.get()
                .uri("/internal/books/{id}/enrichment-input", bookId)
                .headers(this::applyAuth)
                .retrieve()
                .bodyToMono(BookEnrichmentInput.class)
                .block(HTTP_TIMEOUT); // <-- IMPORTANT: prevents stuck "processing"
    }

    public void patch(String bookId, BookEnrichmentPatch patch) {
        wc.patch()
                .uri("/internal/books/{id}/enrichment", bookId)
                .headers(this::applyAuth)
                .bodyValue(patch)
                .retrieve()
                .toBodilessEntity()
                .block(HTTP_TIMEOUT); // <-- IMPORTANT
    }

    private void applyAuth(HttpHeaders h) {
        if (!authToken.isBlank()) {
            h.setBearerAuth(authToken);
        }
    }
}