package com.rxlog.register.web;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** HTTP controller for searching and updating books. */
@RestController
@RequestMapping("/api/register")
public class WebRegisterController {

  private final BookDao bookDao;

  public WebRegisterController(BookDao bookDao) {
    this.bookDao = bookDao;
  }

  @GetMapping("/search")
  public List<BookSearchResult> search(
      @RequestParam(value = "author", required = false) String author,
      @RequestParam(value = "publisher", required = false) String publisher,
      @RequestParam(value = "titleLike", required = false) String titleLike,
      @RequestParam(value = "barcode", required = false) String barcode,
      @RequestParam(value = "readingStatus", required = false) String readingStatus,
      @RequestParam(value = "topBook", required = false) Boolean topBook,
      @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {

    if (limit <= 0) {
      limit = 100;
    } else if (limit > 1000) {
      limit = 1000;
    }

    return bookDao.search(author, publisher, titleLike, barcode, readingStatus, topBook, limit);
  }

  @PatchMapping("/{id}")
  public ResponseEntity<Void> partialUpdate(
      @PathVariable("id") String id, @RequestBody BookUpdateRequest req) {

    boolean updated = bookDao.partialUpdate(id, req);
    if (updated) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.notFound().build();
  }
}
