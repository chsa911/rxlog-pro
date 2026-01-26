package com.rxlog.register.publicapi;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) endpoint that returns how many distinct books currently have at least
 * one barcode assigned.
 *
 * <p>Route: GET /api/public/books/in-stock-count
 */
@RestController
@RequestMapping("/api/public/books")
public class PublicInStockCountController {

  private final JdbcTemplate jdbc;

  public PublicInStockCountController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/in-stock-count")
  public Map<String, Integer> inStockCount() {
    // book_barcodes has UNIQUE(book_id, barcode), so COUNT(DISTINCT book_id) = "books with a barcode".
    Integer cnt =
        jdbc.queryForObject(
            "select count(distinct book_id)::int from book_barcodes", Integer.class);
    return Map.of("count", cnt == null ? 0 : cnt);
  }
}
