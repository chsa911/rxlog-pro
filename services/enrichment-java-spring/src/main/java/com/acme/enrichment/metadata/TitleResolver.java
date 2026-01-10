package com.acme.enrichment.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Resolves book title information by ISBN using outer sources.
 *
 * Strategy:
 *  1) Google Books API
 *  2) OpenLibrary Books API
 *
 * This is best-effort; it may return Optional.empty() if not found or on errors.
 */
@Component
public class TitleResolver {

    private final WebClient http;
    private final ObjectMapper om;

    public TitleResolver(ObjectMapper om, WebClient.Builder webClientBuilder) {
        this.om = om;
        this.http =
                webClientBuilder
                        .build();
    }

    public record ResolvedTitle(
            String fullTitle,
            String title,
            String subtitle,
            String infoUrl,
            String buyUrl,
            String source
    ) {}

    /**
     * Resolve title metadata from external APIs.
     */
    public Optional<ResolvedTitle> resolve(String isbn13Or10) {
        String isbn = normalizeIsbn(isbn13Or10);
        if (isbn == null) return Optional.empty();

        // Google Books first
        Optional<ResolvedTitle> fromGoogle = resolveFromGoogleBooks(isbn);
        if (fromGoogle.isPresent()) return fromGoogle;

        // Fallback OpenLibrary
        return resolveFromOpenLibrary(isbn);
    }

    private Optional<ResolvedTitle> resolveFromGoogleBooks(String isbn) {
        String url = "https://www.googleapis.com/books/v1/volumes?q=isbn:" + isbn;

        try {
            String body =
                    http.get()
                            .uri(url)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(6))
                            .onErrorResume(_e -> Mono.empty())
                            .block();

            if (body == null || body.isBlank()) return Optional.empty();

            JsonNode root = om.readTree(body);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray() || items.size() == 0) return Optional.empty();

            JsonNode item0 = items.get(0);
            JsonNode volumeInfo = item0.get("volumeInfo");
            if (volumeInfo == null || volumeInfo.isNull()) return Optional.empty();

            String title = text(volumeInfo, "title");
            String subtitle = text(volumeInfo, "subtitle");

            if (title == null || title.isBlank()) return Optional.empty();

            String fullTitle = buildFullTitle(title, subtitle);

            // often useful links
            String infoUrl = text(volumeInfo, "infoLink");
            if (infoUrl == null) infoUrl = text(volumeInfo, "previewLink");

            JsonNode saleInfo = item0.get("saleInfo");
            String buyUrl = saleInfo != null ? text(saleInfo, "buyLink") : null;

            return Optional.of(new ResolvedTitle(fullTitle, title, subtitle, infoUrl, buyUrl, "google_books"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<ResolvedTitle> resolveFromOpenLibrary(String isbn) {
        String url =
                "https://openlibrary.org/api/books?bibkeys=ISBN:"
                        + isbn
                        + "&format=json&jscmd=data";

        try {
            String body =
                    http.get()
                            .uri(url)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(6))
                            .onErrorResume(_e -> Mono.empty())
                            .block();

            if (body == null || body.isBlank()) return Optional.empty();

            JsonNode root = om.readTree(body);
            JsonNode book = root.get("ISBN:" + isbn);
            if (book == null || book.isNull()) return Optional.empty();

            String title = text(book, "title");
            if (title == null || title.isBlank()) return Optional.empty();

            // OpenLibrary usually doesn't provide "subtitle" explicitly
            String fullTitle = title;

            // info link
            String infoUrl = null;
            String urlPath = text(book, "url"); // often "/books/OL..."
            if (urlPath != null && !urlPath.isBlank()) infoUrl = "https://openlibrary.org" + urlPath;
            if (infoUrl == null) {
                String key = text(book, "key");
                if (key != null && !key.isBlank()) infoUrl = "https://openlibrary.org" + key;
            }

            return Optional.of(new ResolvedTitle(fullTitle, title, null, infoUrl, null, "openlibrary"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String normalizeIsbn(String s) {
        if (s == null) return null;
        String cleaned = s.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (cleaned.length() == 10 || cleaned.length() == 13) return cleaned;
        return null;
    }

    private static String buildFullTitle(String title, String subtitle) {
        if (subtitle == null || subtitle.isBlank()) return title;
        // Common formatting in catalogs
        return title + ": " + subtitle;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }
}