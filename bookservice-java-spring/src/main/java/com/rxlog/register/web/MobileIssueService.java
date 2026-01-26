package com.rxlog.register.web;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MobileIssueService {

    private final JdbcTemplate jdbc;
    private final BookDao bookDao;

    public MobileIssueService(JdbcTemplate jdbc, BookDao bookDao) {
        this.jdbc = jdbc;
        this.bookDao = bookDao;
    }

    public List<MobileIssueDto> listIssues(String status, int limit) {
        String st = status == null ? "open" : status.trim().toLowerCase(Locale.ROOT);
        int lim = Math.min(500, Math.max(1, limit));

        String sql;
        Object[] args;
        if ("all".equals(st)) {
            sql = """
                  select issue_id::text, client_change_id, barcode, pages,
                         reading_status, reading_status_changed_at,
                         top_book, top_book_set_at,
                         error_code, status, created_at
                  from mobile_sync_issues
                  order by created_at desc
                  limit ?
                  """;
            args = new Object[] {lim};
        } else {
            sql = """
                  select issue_id::text, client_change_id, barcode, pages,
                         reading_status, reading_status_changed_at,
                         top_book, top_book_set_at,
                         error_code, status, created_at
                  from mobile_sync_issues
                  where status = ?
                  order by created_at desc
                  limit ?
                  """;
            args = new Object[] {st, lim};
        }

        return jdbc.query(
                sql,
                (rs, i) -> new MobileIssueDto(
                        rs.getString("issue_id"),
                        rs.getString("client_change_id"),
                        rs.getString("barcode"),
                        (Integer) rs.getObject("pages"),
                        rs.getString("reading_status"),
                        rs.getTimestamp("reading_status_changed_at") != null
                                ? rs.getTimestamp("reading_status_changed_at").toInstant().toString()
                                : null,
                        (Boolean) rs.getObject("top_book"),
                        rs.getTimestamp("top_book_set_at") != null
                                ? rs.getTimestamp("top_book_set_at").toInstant().toString()
                                : null,
                        rs.getString("error_code"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        messageFor(rs.getString("error_code"))
                ),
                args);
    }

    @Transactional
    public void resolve(String issueId, String bookId) {
        IssueRow issue = loadIssue(issueId);
        if (!"open".equalsIgnoreCase(issue.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue is not open");
        }

        // Apply the changes stored in the issue to the selected book.
        if (issue.readingStatus != null && issue.readingStatusChangedAt != null) {
            bookDao.applyReadingStatusWithTimestamp(bookId, issue.readingStatus, issue.readingStatusChangedAt);
        }
        if (issue.topBook != null) {
            bookDao.applyTopBookWithTimestamp(bookId, issue.topBook, issue.topBookSetAt);
        }

        jdbc.update(
                """
                update mobile_sync_issues
                set status = 'resolved', resolved_book_id = ?::uuid, resolved_at = now()
                where issue_id = ?::uuid
                """,
                bookId,
                issueId);

        // Update receipt so future replays return applied.
        jdbc.update(
                """
                update mobile_sync_receipts
                set status = 'applied', book_id = ?::uuid, issue_id = null, error_code = null
                where client_change_id = ?
                """,
                bookId,
                issue.clientChangeId);
    }

    @Transactional
    public void ignore(String issueId) {
        IssueRow issue = loadIssue(issueId);

        jdbc.update(
                """
                update mobile_sync_issues
                set status = 'ignored', resolved_book_id = null, resolved_at = now()
                where issue_id = ?::uuid
                """,
                issueId);

        jdbc.update(
                """
                update mobile_sync_receipts
                set status = 'rejected', book_id = null, issue_id = null, error_code = 'IGNORED'
                where client_change_id = ?
                """,
                issue.clientChangeId);
    }

    /**
     * Retry attempts to resolve the issue automatically using current DB state.
     * If a unique book can be resolved, it applies the change and marks the issue resolved.
     */
    @Transactional
    public void retry(String issueId) {
        IssueRow issue = loadIssue(issueId);
        if (!"open".equalsIgnoreCase(issue.status)) return;
        if (issue.barcode == null || issue.barcode.isBlank()) return;

        List<BookSearchResult> candidates =
                bookDao.search(null, null, null, issue.barcode, null, null, 200);

        BookSearchResult resolved = resolveByPages(candidates, issue.pages);
        if (resolved == null) return;

        resolve(issueId, resolved.getId());
    }

    private static BookSearchResult resolveByPages(List<BookSearchResult> candidates, Integer pagesMobile) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        if (pagesMobile == null) return null;

        // exact match
        List<BookSearchResult> exact = new ArrayList<>();
        for (var c : candidates) {
            if (c.getPages() != null && c.getPages().equals(pagesMobile)) exact.add(c);
        }
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return null;

        // tolerant match ±5%
        record Scored(BookSearchResult b, int diff) {}
        List<Scored> tol = new ArrayList<>();
        for (var c : candidates) {
            Integer pdb = c.getPages();
            if (pdb == null || pdb <= 0) continue;
            int diff = Math.abs(pagesMobile - pdb);
            int tolPages = Math.max(1, (int) Math.round(pdb * 0.05));
            if (diff <= tolPages) tol.add(new Scored(c, diff));
        }
        if (tol.isEmpty()) return null;
        tol.sort(Comparator.comparingInt(Scored::diff));
        int best = tol.get(0).diff();
        List<Scored> ties = tol.stream().filter(s -> s.diff() == best).toList();
        if (ties.size() != 1) return null;
        return ties.get(0).b();
    }

    private IssueRow loadIssue(String issueId) {
        try {
            return jdbc.queryForObject(
                    """
                    select issue_id::text, client_change_id, barcode, pages,
                           reading_status, reading_status_changed_at,
                           top_book, top_book_set_at,
                           error_code, status
                    from mobile_sync_issues
                    where issue_id = ?::uuid
                    """,
                    (rs, i) -> {
                        Instant rsAt = null;
                        Instant tbAt = null;
                        var rsTs = rs.getTimestamp("reading_status_changed_at");
                        if (rsTs != null) rsAt = rsTs.toInstant();
                        var tbTs = rs.getTimestamp("top_book_set_at");
                        if (tbTs != null) tbAt = tbTs.toInstant();

                        return new IssueRow(
                                rs.getString("issue_id"),
                                rs.getString("client_change_id"),
                                rs.getString("barcode"),
                                (Integer) rs.getObject("pages"),
                                rs.getString("reading_status"),
                                rsAt,
                                (Boolean) rs.getObject("top_book"),
                                tbAt,
                                rs.getString("error_code"),
                                rs.getString("status"));
                    },
                    issueId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found: " + issueId);
        }
    }

    private static String messageFor(String code) {
        if (code == null) return "";
        return switch (code) {
            case "BARCODE_NOT_FOUND" -> "Barcode nicht gefunden";
            case "AMBIGUOUS_BARCODE_PAGES_REQUIRED" -> "Mehrere Bücher zu Barcode (Pages fehlt)";
            case "NO_UNIQUE_MATCH" -> "Kein eindeutiger Treffer";
            default -> code;
        };
    }

    private record IssueRow(
            String issueId,
            String clientChangeId,
            String barcode,
            Integer pages,
            String readingStatus,
            Instant readingStatusChangedAt,
            Boolean topBook,
            Instant topBookSetAt,
            String errorCode,
            String status) {}
}
