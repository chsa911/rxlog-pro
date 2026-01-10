package com.rxlog.register.api;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/register/isbn")
public class IsbnSearchController {

    private final DnbSruIsbnSearchService dnb;

    public IsbnSearchController(DnbSruIsbnSearchService dnb) {
        this.dnb = dnb;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String publisher,
            @RequestParam(defaultValue = "8") int limit
    ) {
        boolean any =
                StringUtils.hasText(author) || StringUtils.hasText(title) || StringUtils.hasText(publisher);
        if (!any) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "please_provide_author_or_title_or_publisher"));
        }

        try {
            List<IsbnCandidate> res = dnb.search(author, title, publisher, limit);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(502)
                    .body(java.util.Map.of("error", "dnb_lookup_failed", "message", String.valueOf(e.getMessage())));
        }
    }
}