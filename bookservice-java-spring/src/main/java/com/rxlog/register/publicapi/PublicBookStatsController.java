package com.rxlog.register.publicapi;

import java.time.LocalDate;
import java.time.Year;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) reading stats used by the homepage.
 *
 * <p>Route: GET /api/public/books/stats?year=2026
 *
 * <p>Returns counts for the given year:
 *
 * <ul>
 *   <li>finished: books whose reading_status is 'finished' and reading_status_updated_at is in the year
 *   <li>abandoned: books whose reading_status is 'abandoned' and reading_status_updated_at is in the year
 *   <li>top: books where top_book is true and top_book_set_at is in the year
 * </ul>
 */
@RestController
@RequestMapping("/api/public/books")
public class PublicBookStatsController {

  private final JdbcTemplate jdbc;

  public PublicBookStatsController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping("/stats")
  public Map<String, Integer> stats(
      @RequestParam(required = false) Integer year) {

    int y = (year == null) ? Year.now().getValue() : year;
    if (y < 1900) y = 1900;
    if (y > 3000) y = 3000;

    LocalDate from = LocalDate.of(y, 1, 1);
    LocalDate to = from.plusYears(1);

    String sql =
        """
            select
              sum(case when reading_status = 'finished'
                        and reading_status_updated_at >= ?
                        and reading_status_updated_at <  ?
                       then 1 else 0 end)::int as finished,
              sum(case when reading_status = 'abandoned'
                        and reading_status_updated_at >= ?
                        and reading_status_updated_at <  ?
                       then 1 else 0 end)::int as abandoned,
              sum(case when top_book = true
                        and top_book_set_at >= ?
                        and top_book_set_at <  ?
                       then 1 else 0 end)::int as top
            from books
            """;

    return jdbc.queryForObject(
        sql,
        (rs, rowNum) ->
            Map.of(
                "finished", rs.getInt("finished"),
                "abandoned", rs.getInt("abandoned"),
                "top", rs.getInt("top")),
        from,
        to,
        from,
        to,
        from,
        to);
  }
}
