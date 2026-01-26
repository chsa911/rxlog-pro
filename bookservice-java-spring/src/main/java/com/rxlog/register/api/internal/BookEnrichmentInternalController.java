package com.rxlog.register.api.internal;

import com.rxlog.register.dto.BookEnrichmentInput;
import com.rxlog.register.dto.BookEnrichmentPatch;
import com.rxlog.register.service.BookEnrichmentService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/internal/books")
public class BookEnrichmentInternalController {

    private final BookEnrichmentService service;

    public BookEnrichmentInternalController(BookEnrichmentService service) {
        this.service = service;
    }

    @GetMapping(
            value = "/{id}/enrichment-input",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<BookEnrichmentInput> getEnrichmentInput(@PathVariable("id") String id) {
        return ResponseEntity.ok(service.getEnrichmentInput(id));
    }

    @PatchMapping(
            value = "/{id}/enrichment",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> patchEnrichment(@PathVariable("id") String id,
                                                @Valid @RequestBody BookEnrichmentPatch patch) {
        service.applyPatch(id, patch);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        // your service throws this when the bookId does not exist
        if (ex.getMessage() != null && ex.getMessage().startsWith("Book not found:")) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage() == null ? "Bad request" : ex.getMessage()));
    }
}