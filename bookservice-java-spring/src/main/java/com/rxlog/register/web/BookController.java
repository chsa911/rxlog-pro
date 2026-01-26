package com.rxlog.register.web;

import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/** REST API for registering and updating books. */
@RestController
@RequestMapping("/api/register")
public class BookController {

  private final BookDao dao;

  public BookController(BookDao dao) {
    this.dao = dao;
  }

  @GetMapping("/books")
  public List<BookSearchResult> search(
      @RequestParam(required = false) String author,
      @RequestParam(required = false) String publisher,
      @RequestParam(name = "title", required = false) String titleKeywordLike,
      @RequestParam(required = false) String barcode,
      @RequestParam(required = false) String isFiction,
      @RequestParam(required = false) String genre,
      @RequestParam(required = false) String subGenre,
      @RequestParam(required = false) String theme,
      @RequestParam(required = false) String readingStatus,
      @RequestParam(required = false) String topBook,
      @RequestParam(required = false, defaultValue = "20") Integer limit) {
    Boolean top = null;
    if ("true".equalsIgnoreCase(topBook)) top = Boolean.TRUE;
    if ("false".equalsIgnoreCase(topBook)) top = Boolean.FALSE;

    String rs = null;
    if (StringUtils.hasText(readingStatus)) {
      String v = readingStatus.trim().toLowerCase();
      Set<String> allowed = Set.of("in_progress", "finished", "abandoned");
      if (allowed.contains(v)) rs = v;
    }

    if (limit == null || limit <= 0 || limit > 500) limit = 20;

    Boolean fiction = null;
    boolean onlyUnspecifiedFiction = false;
    if (StringUtils.hasText(isFiction)) {
      String v = isFiction.trim().toLowerCase();
      if ("true".equals(v)) fiction = Boolean.TRUE;
      else if ("false".equals(v)) fiction = Boolean.FALSE;
      else if ("null".equals(v) || "unknown".equals(v) || "unspecified".equals(v)) {
        onlyUnspecifiedFiction = true;
      }
    }

    return dao.search(
        author,
        publisher,
        titleKeywordLike,
        barcode,
        rs,
        top,
        fiction,
        onlyUnspecifiedFiction,
        genre,
        subGenre,
        theme,
        limit);
  }

  @PatchMapping("/books/{id}")
  public ResponseEntity<?> patch(@PathVariable String id, @RequestBody BookUpdateRequest req) {
    if (req.getReadingStatus() != null) {
      String v = req.getReadingStatus().trim().toLowerCase();
      if (!Set.of("in_progress", "finished", "abandoned").contains(v)) {
        return ResponseEntity.badRequest()
            .body(
                java.util.Map.of(
                    "error",
                    "invalid_reading_status",
                    "allowed",
                    new String[] {"in_progress", "finished", "abandoned"}));
      }
      req.setReadingStatus(v);
    }

    boolean changed = dao.partialUpdate(id, req);
    if (!changed) return ResponseEntity.noContent().build();
    return ResponseEntity.ok(java.util.Map.of("ok", true));
  }
}
