package com.rxlog.register.web;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import static com.rxlog.register.web.MobileSyncRequest.MobileChange;

@Service
public class MobileSyncService {

    private static final Set<String> ALLOWED_READING =
            Set.of("in_progress", "finished", "abandoned");

    private final JdbcTemplate jdbc;
    private final BookDao bookDao;

    public MobileSyncService(JdbcTemplate jdbc, BookDao bookDao) {
        this.jdbc = jdbc;
        this.bookDao = bookDao;
    }

    public MobileSyncResponse sync(MobileSyncRequest req) {
        List<MobileSyncResult> out = new ArrayList<>();
        List<MobileChange> changes = req != null && req.changes() != null ? req.changes() : List.of();

        for (MobileChange c : changes) {
            out.add(processOneIdempotent(c));
        }
        return new MobileSyncResponse(out);
    }

    private MobileSyncResult processOneIdempotent(MobileChange c) {
        if (c == null || !StringUtils.hasText(c.clientChangeId())) {
            return new MobileSyncResult(null, "rejected", null, null, "MISSING_CLIENT_CHANGE_ID");
        }

        // idempotency: if already processed, return stored receipt
        var rows =
                jdbc.query(
                        "select status, book_id::text, issue_id::text, error_code from mobile_sync_receipts where client_change_id = ?",
                        (rs, i) ->
                                new MobileSyncResult(
                                        c.clientChangeId(),
                                        rs.getString("status"),
                                        rs.getString("book_id"),
                                        rs.getString("issue_id"),
                                        rs.getString("error_code")),
                        c.clientChangeId());

        if (!rows.isEmpty()) return rows.get(0);

        // otherwise process and store receipt
        MobileSyncResult res = processOne(c);

        jdbc.update(
                """
                insert into mobile_sync_receipts (client_change_id, status, book_id, issue_id, error_code)
                values (?, ?, ?::uuid, ?::uuid, ?)
                on conflict (client_change_id) do nothing
                """,
                c.clientChangeId(),
                res.status(),
                res.bookId(),
                res.issueId(),
                res.errorCode());

        return res;
    }

    @Transactional
    protected MobileSyncResult processOne(MobileChange c) {
        // Validate + normalize barcode (LOOKUP only, tolerant for legacy codes)
        if (!StringUtils.hasText(c.barcode())) {
            return reject(c, "MISSING_BARCODE");
        }

        String normalizedBarcode = normalizeBarcodeForLookupOrNull(c.barcode());
        if (normalizedBarcode == null) {
            return reject(c, "INVALID_BARCODE_FORMAT");
        }

        // Normalize reading status if provided
        String readingStatus = null;
        Instant readingChangedAt = null;

        if (c.readingStatus() != null) {
            readingStatus = c.readingStatus().trim().toLowerCase();
            if (!ALLOWED_READING.contains(readingStatus)) {
                return reject(c, "INVALID_READING_STATUS");
            }
            if (!StringUtils.hasText(c.readingStatusChangedAt())) {
                return reject(c, "MISSING_READING_STATUS_TIMESTAMP");
            }
            readingChangedAt = parseInstant(c.readingStatusChangedAt());
            if (readingChangedAt == null) return reject(c, "INVALID_READING_STATUS_TIMESTAMP");
        }

        // TopBook optional
        Boolean topBook = c.topBook();
        Instant topBookSetAt = null;
        if (Boolean.TRUE.equals(topBook)) {
            if (!StringUtils.hasText(c.topBookSetAt())) {
                return reject(c, "MISSING_TOP_BOOK_SET_TIMESTAMP");
            }
            topBookSetAt = parseInstant(c.topBookSetAt());
            if (topBookSetAt == null) return reject(c, "INVALID_TOP_BOOK_SET_TIMESTAMP");
        }

        // Resolve candidates by barcode (active barcodes only)
        List<BookSearchResult> candidates =
                bookDao.search(
                        null, null, null,
                        normalizedBarcode,
                        null, null,
                        200);

        BookSearchResult resolved = resolveByPages(candidates, c.pages());

        if (resolved == null) {
            String issueId = storeIssue(c, normalizedBarcode, candidates);
            return new MobileSyncResult(
                    c.clientChangeId(),
                    "needs_review",
                    null,
                    issueId,
                    issueErrorCode(candidates, c.pages()));
        }

        // Apply updates
        String bookId = resolved.getId();

        if (readingStatus != null) {
            bookDao.applyReadingStatusWithTimestamp(bookId, readingStatus, readingChangedAt);
        }
        if (topBook != null) {
            bookDao.applyTopBookWithTimestamp(bookId, topBook, topBookSetAt);
        }

        return new MobileSyncResult(c.clientChangeId(), "applied", bookId, null, null);
    }

    private MobileSyncResult reject(MobileChange c, String code) {
        return new MobileSyncResult(c.clientChangeId(), "rejected", null, null, code);
    }

    private Instant parseInstant(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * LOOKUP normalization for mobile sync:
     * - trim + lowercase
     * - accept numeric (EAN/ISBN) and any alphanumeric legacy shelf code
     * - reject anything with spaces/special chars
     */
    private String normalizeBarcodeForLookupOrNull(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return null;

        // accept digits-only and alphanumeric legacy codes (li010, lk101, og211, rn010, dg100, ...)
        if (s.matches("^[a-z0-9]+$")) {
            return s;
        }
        return null;
    }

    // -------- resolution logic (barcode duplicates + pages ±5%) --------

    private BookSearchResult resolveByPages(List<BookSearchResult> candidates, Integer pagesMobile) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);

        if (pagesMobile == null) return null;

        // 1) exact match
        List<BookSearchResult> exact = new ArrayList<>();
        for (var c : candidates) {
            if (c.getPages() != null && c.getPages().equals(pagesMobile)) exact.add(c);
        }
        if (exact.size() == 1) return exact.get(0);
        if (exact.size() > 1) return null;

        // 2) tolerant match ±5%
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
        List<Scored> ties = tol.stream().filter(x -> x.diff() == best).toList();
        if (ties.size() != 1) return null;
        return ties.get(0).b();
    }

    private String issueErrorCode(List<BookSearchResult> candidates, Integer pagesMobile) {
        if (candidates == null || candidates.isEmpty()) return "BARCODE_NOT_FOUND";
        if (candidates.size() > 1 && pagesMobile == null) return "AMBIGUOUS_BARCODE_PAGES_REQUIRED";
        return "NO_UNIQUE_MATCH";
    }

    private String storeIssue(MobileChange c, String normalizedBarcode, List<BookSearchResult> candidates) {
        String errorCode = issueErrorCode(candidates, c.pages());

        Instant rsAt = parseInstant(c.readingStatusChangedAt());
        Instant tbAt = parseInstant(c.topBookSetAt());

        UUID issueId = UUID.randomUUID();
        jdbc.update(
                """
                insert into mobile_sync_issues (
                  issue_id, client_change_id, barcode, pages,
                  reading_status, reading_status_changed_at,
                  top_book, top_book_set_at,
                  error_code, status
                ) values (
                  ?::uuid, ?, ?, ?,
                  ?, ?,
                  ?, ?,
                  ?, 'open'
                )
                on conflict (client_change_id) do nothing
                """,
                issueId.toString(),
                c.clientChangeId(),
                normalizedBarcode,
                c.pages(),
                c.readingStatus() != null ? c.readingStatus().trim().toLowerCase() : null,
                rsAt != null ? Timestamp.from(rsAt) : null,
                c.topBook(),
                tbAt != null ? Timestamp.from(tbAt) : null,
                errorCode);

        return issueId.toString();
    }
}