// services/bookservice-java-spring/src/main/java/com/rxlog/register/api/IsbnLookupController.java
package com.rxlog.register.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/register")
public class IsbnLookupController {

    private final RestClient http = RestClient.create();
    private final ObjectMapper om;

    public IsbnLookupController(ObjectMapper om) {
        this.om = om;
    }

    public record IsbnLookupResponse(
            String isbn,
            String title,
            List<String> authors,
            String publisher,
            Integer pages,
            String coverUrl
    ) {}

    @GetMapping("/isbn/{isbn}")
    public ResponseEntity<IsbnLookupResponse> lookup(@PathVariable String isbn) throws Exception {
        String clean = isbn == null ? "" : isbn.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (!(clean.length() == 10 || clean.length() == 13)) {
            return ResponseEntity.badRequest().build();
        }

        String url =
                "https://openlibrary.org/api/books?bibkeys=ISBN:" + clean + "&format=json&jscmd=data";

        String body = http.get().uri(url).retrieve().body(String.class);
        JsonNode root = om.readTree(body);
        JsonNode book = root.get("ISBN:" + clean);
        if (book == null || book.isMissingNode()) {
            return ResponseEntity.notFound().build();
        }

        String title = text(book, "title");

        // authors: [{name: "..."}]
        List<String> authors = new ArrayList<>();
        JsonNode a = book.get("authors");
        if (a != null && a.isArray()) {
            for (JsonNode n : a) {
                String name = text(n, "name");
                if (name != null && !name.isBlank()) authors.add(name);
            }
        }

        // publishers: [{name: "..."}]
        String publisher = null;
        JsonNode p = book.get("publishers");
        if (p != null && p.isArray() && p.size() > 0) {
            publisher = text(p.get(0), "name");
        }

        Integer pages = book.has("number_of_pages") ? book.get("number_of_pages").asInt() : null;

        String coverUrl = null;
        JsonNode cover = book.get("cover");
        if (cover != null) {
            coverUrl = text(cover, "medium");
            if (coverUrl == null) coverUrl = text(cover, "large");
            if (coverUrl == null) coverUrl = text(cover, "small");
        }

        return ResponseEntity.ok(new IsbnLookupResponse(clean, title, authors, publisher, pages, coverUrl));
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }
}




