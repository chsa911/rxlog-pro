package com.rxlog.register.web;

import com.rxlog.register.api.RegisterBookRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** JDBC-basierter Zugriff auf Bücher + Barcodes. */
@Repository
public class BookDao {

    private static final Logger log = LoggerFactory.getLogger(BookDao.class);

    private final JdbcTemplate jdbc;
    private final BarcodeClient barcodeClient;

    public BookDao(JdbcTemplate jdbc, BarcodeClient barcodeClient) {
        this.jdbc = jdbc;
        this.barcodeClient = barcodeClient;
    }

    private static BookSearchResult mapRow(ResultSet rs) throws SQLException {
        BookSearchResult r = new BookSearchResult();
        r.setId(rs.getString("id"));
        r.setAuthor(rs.getString("author"));
        r.setPublisher(rs.getString("publisher"));
        r.setPages((Integer) rs.getObject("pages"));
        r.setReadingStatus(rs.getString("reading_status"));
        Boolean top = (Boolean) rs.getObject("top_book");
        r.setTopBook(top != null ? top : Boolean.FALSE);
        r.setWidth((Integer) rs.getObject("width"));
        r.setHeight((Integer) rs.getObject("height"));

        String csv = rs.getString("barcodes_csv");
        if (csv != null && !csv.isBlank()) {
            String[] parts = csv.split(",");
            List<String> list = new ArrayList<>();
            for (String p : parts) {
                String s = p.trim();
                if (!s.isEmpty()) list.add(s);
            }
            r.setBarcodes(list);
        } else {
            r.setBarcodes(Collections.emptyList());
        }
        return r;
    }

    // ------------------------------------------------------------
    // Search (Admin UI)
    // ------------------------------------------------------------
    public List<BookSearchResult> search(
            String author,
            String publisher,
            String titleLike,
            String barcode,
            String readingStatus,
            Boolean topBook,
            int limit) {

        StringBuilder sql =
                new StringBuilder(
                        """
                            select
                              b.id,
                              b.author,
                              b.publisher,
                              b.pages,
                              b.reading_status,
                              b.top_book,
                              b.width,
                              b.height,
                              string_agg(distinct bb.barcode, ',') as barcodes_csv
                            from books b
                            left join book_barcodes bb on bb.book_id = b.id
                            """);

        List<Object> args = new ArrayList<>();
        List<String> where = new ArrayList<>();

        if (StringUtils.hasText(author)) {
            where.add("b.author ilike ?");
            args.add("%" + author.trim() + "%");
        }
        if (StringUtils.hasText(publisher)) {
            where.add("b.publisher ilike ?");
            args.add("%" + publisher.trim() + "%");
        }
        if (StringUtils.hasText(titleLike)) {
            where.add("(b.title_keyword ilike ? or b.title_keyword2 ilike ? or b.title_keyword3 ilike ?)");
            String pat = "%" + titleLike.trim() + "%";
            args.add(pat);
            args.add(pat);
            args.add(pat);
        }
        if (StringUtils.hasText(barcode)) {
            String code = BarcodeNormalizer.normalizeOrNull(barcode);
            if (code == null) return Collections.emptyList();
            where.add(
                    """
                    exists (
                      select 1 from book_barcodes bb2
                      where bb2.book_id = b.id and bb2.barcode = ?
                    )
                    """);
            args.add(code);
        }
        if (StringUtils.hasText(readingStatus)) {
            where.add("b.reading_status = ?");
            args.add(readingStatus.trim());
        }
        if (topBook != null) {
            where.add("b.top_book = ?");
            args.add(topBook);
        }

        if (!where.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", where)).append(" ");
        }

        sql.append(
                """
                        group by b.id
                        order by b.registered_at desc nulls last, b.id desc
                        limit ?
                        """);
        args.add(limit);

        return this.jdbc.query(sql.toString(), args.toArray(), (rs, i) -> mapRow(rs));
    }

    // ------------------------------------------------------------
    // Partial-Update (Admin UI)
    // ------------------------------------------------------------

    @Transactional
    public boolean partialUpdate(String id, BookUpdateRequest req) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        boolean freeBarcode = false;

        if (req.getPages() != null) {
            sets.add("pages = ?");
            args.add(req.getPages());
        }

        if (req.getReadingStatus() != null) {
            sets.add("reading_status = ?");
            args.add(req.getReadingStatus());
            sets.add("reading_status_updated_at = now()");

            String rs = req.getReadingStatus();
            if ("finished".equals(rs) || "abandoned".equals(rs)) {
                freeBarcode = true;
            }
        }

        if (req.getTopBook() != null) {
            sets.add("top_book = ?");
            args.add(req.getTopBook());
            if (Boolean.TRUE.equals(req.getTopBook())) {
                sets.add("top_book_set_at = coalesce(top_book_set_at, now())");
            }
        }

        if (req.getWidth() != null) {
            sets.add("width = ?");
            args.add(req.getWidth());
        }

        if (req.getHeight() != null) {
            sets.add("height = ?");
            args.add(req.getHeight());
        }

        int updated = 0;
        if (!sets.isEmpty()) {
            String sql = "update books set " + String.join(", ", sets) + " where id = ?::uuid";
            args.add(id);
            updated = this.jdbc.update(sql, args.toArray());
        }

        // --- Barcode handling ------------------------------------

        // If readingStatus finished/abandoned: write barcodes_history + free barcode (delete from book_barcodes)
        if (freeBarcode) {
            // usedTo must be reading_status_updated_at (which is set to now() above)
            Timestamp ts =
                    this.jdbc.queryForObject(
                            "select reading_status_updated_at from books where id = ?::uuid",
                            Timestamp.class,
                            id);
            Instant usedTo = ts != null ? ts.toInstant() : Instant.now();

            String reason = req.getReadingStatus();
            releaseBarcodeToHistoryAndDelete(id, usedTo, reason);
            ++updated;

        } else if (req.getBarcodes() != null) {
            // Rule: one book has one barcode, and barcode should not be changed.
            LinkedHashSet<String> uniq = new LinkedHashSet<>();
            for (String b : req.getBarcodes()) {
                if (b != null) {
                    String norm = BarcodeNormalizer.normalizeOrNull(b);
                    if (norm != null) uniq.add(norm);
                    else throw new IllegalArgumentException("Invalid barcode: " + b);
                }
            }
            if (uniq.isEmpty()) return updated > 0;

            if (uniq.size() > 1) {
                throw new IllegalArgumentException("Only one barcode per book is allowed.");
            }

            String newCode = uniq.iterator().next();

            String existing =
                    this.jdbc.query(
                            "select barcode from book_barcodes where book_id = ?::uuid limit 1",
                            (rs) -> rs.next() ? rs.getString(1) : null,
                            id);

            if (existing != null && !existing.isBlank() && !existing.equals(newCode)) {
                throw new IllegalArgumentException(
                        "Barcode cannot be changed (existing=" + existing + ", requested=" + newCode + ")");
            }

            if (existing == null || existing.isBlank()) {
                // usedFrom should be registered_at
                Timestamp regTs =
                        this.jdbc.queryForObject(
                                "select registered_at from books where id = ?::uuid",
                                Timestamp.class,
                                id);
                Instant usedFrom = regTs != null ? regTs.toInstant() : Instant.now();

                this.jdbc.update(
                        "insert into book_barcodes (book_id, barcode) values (?::uuid, ?)",
                        id,
                        newCode);

                safeUsageAssign(newCode, id, usedFrom);
                ++updated;
            }
        }

        return updated > 0;
    }

    // ------------------------------------------------------------
    // Insert for Registration
    // ------------------------------------------------------------

    private record InsertResult(String id, Instant registeredAt) {}

    /** Creates a new book + its barcode and returns the generated ID */
    @Transactional
    public String insert(RegisterBookRequest req) {
        boolean top = req.topBook() != null && req.topBook();

        String sql =
                """
                insert into books (
                    author,
                    publisher,
                    pages,
                    title_keyword,
                    title_keyword_position,
                    title_keyword2,
                    title_keyword2_position,
                    title_keyword3,
                    title_keyword3_position,
                    width,
                    height,
                    reading_status,
                    top_book,
                    registered_at,
                    reading_status_updated_at
                )
                values (
                    ?, ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    ?, ?,
                    now(),
                    now()
                )
                returning id::text, registered_at
                """;

        InsertResult res =
                jdbc.queryForObject(
                        sql,
                        (rs, i) ->
                                new InsertResult(
                                        rs.getString(1),
                                        rs.getTimestamp(2).toInstant()),
                        req.author(),
                        req.publisher(),
                        req.pages(),
                        req.titleKeyword(),
                        req.titleKeywordPosition(),
                        req.titleKeyword2(),
                        req.titleKeyword2Position(),
                        req.titleKeyword3(),
                        req.titleKeyword3Position(),
                        req.width(),
                        req.height(),
                        req.readingStatus(),
                        top);

        if (res == null || res.id() == null) {
            throw new IllegalStateException("Insert returned null id");
        }

        String id = res.id();
        Instant registeredAt = res.registeredAt();

        if (req.barcode() != null && !req.barcode().isBlank()) {
            String code = BarcodeNormalizer.normalizeOrThrow(req.barcode());
            jdbc.update(
                    "insert into book_barcodes (book_id, barcode) values (?::uuid, ?)",
                    id,
                    code);

            // usedFrom must be registered_at (best-effort: do not fail registration on usage logging)
            safeUsageAssign(code, id, registeredAt);
        }

        // employerfriendly: enrichment scheduling must NOT break registration
        try {
            jdbc.update(
                    """
                    insert into book_enrichment_job (book_id, status, attempts, next_run_at, updated_at, last_error)
                    values (?::uuid, 'queued', 0, now(), now(), null)
                    on conflict (book_id) do update
                      set status      = 'queued',
                          attempts    = 0,
                          next_run_at = now(),
                          updated_at  = now(),
                          last_error  = null
                     """,
                    id);
        } catch (Exception e) {
            log.warn("Could not schedule enrichment job for book {} (ignored): {}", id, e.getMessage());
        }

        return id;
    }

    // ------------------------------------------------------------
    // Mobile-sync helpers (write MOBILE timestamps)
    // ------------------------------------------------------------

    /**
     * Update reading_status using a MOBILE timestamp (not now()).
     * If status becomes finished/abandoned: release barcode + write barcodes_history + delete from book_barcodes.
     */
    @Transactional
    public void applyReadingStatusWithTimestamp(String id, String readingStatus, Instant changedAt) {
        jdbc.update(
                "update books set reading_status = ?, reading_status_updated_at = ? where id = ?::uuid",
                readingStatus,
                Timestamp.from(changedAt),
                id
        );

        if ("finished".equals(readingStatus) || "abandoned".equals(readingStatus)) {
            releaseBarcodeToHistoryAndDelete(id, changedAt, readingStatus);
        }
    }

    /**
     * Writes one (or more legacy) barcodes to barcodes_history and deletes them from book_barcodes
     * (so the barcode becomes reusable).
     *
     * Order requirement: barcodes_history.created_at must be chronological:
     * - mobile: use readingStatusChangedAt
     * - admin: use reading_status_updated_at (now())
     */
    private void releaseBarcodeToHistoryAndDelete(String bookId, Instant releaseAt, String reason) {
        List<String> codes =
                this.jdbc.query(
                        "select barcode from book_barcodes where book_id = ?::uuid",
                        (rs, i) -> rs.getString(1),
                        bookId);

        if (codes.isEmpty()) return;

        for (String code : codes) {
            if (code == null || code.isBlank()) continue;

            safeUsageRelease(code, bookId, releaseAt, reason);
            safeRelease(code);

            // employerfriendly history: only (book_id, barcode, created_at) needed; book data via join on book_id
            jdbc.update(
                    "insert into barcodes_history (book_id, barcode, created_at) values (?::uuid, ?, ?)",
                    bookId,
                    code,
                    Timestamp.from(releaseAt)
            );
        }

        // delete active barcode(s) so they can be reused
        this.jdbc.update("delete from book_barcodes where book_id = ?::uuid", bookId);
    }

    private void safeUsageAssign(String barcode, String bookId, Instant usedFrom) {
        try {
            barcodeClient.usageAssign(barcode, bookId, usedFrom);
        } catch (Exception e) {
            log.warn("usageAssign failed for barcode={} bookId={} (ignored): {}", barcode, bookId, e.getMessage());
        }
    }

    private void safeUsageRelease(String barcode, String bookId, Instant usedTo, String reason) {
        try {
            barcodeClient.usageRelease(barcode, bookId, usedTo, reason);
        } catch (Exception e) {
            log.warn("usageRelease failed for barcode={} bookId={} (ignored): {}", barcode, bookId, e.getMessage());
        }
    }

    private void safeRelease(String barcode) {
        try {
            barcodeClient.release(barcode);
        } catch (Exception e) {
            log.warn("barcode release failed for barcode={} (ignored): {}", barcode, e.getMessage());
        }
    }

    // ------------------------------------------------------------
    // Analytics
    // ------------------------------------------------------------
    public java.util.List<TopAuthorStat> topAuthors(java.util.List<String> statuses, int limit) {

        StringBuilder sql = new StringBuilder("""
        select
          coalesce(nullif(btrim(author), ''), '(unknown)') as author,
          sum(case when reading_status = 'finished' then 1 else 0 end)::bigint as finished,
          sum(case when reading_status = 'abandoned' then 1 else 0 end)::bigint as abandoned,
          count(*)::bigint as total,
          coalesce(sum(pages), 0)::bigint as pages
        from books
        """);

        java.util.List<Object> args = new java.util.ArrayList<>();

        if (statuses != null && !statuses.isEmpty()) {
            sql.append(" where reading_status in (");
            for (int i = 0; i < statuses.size(); i++) {
                if (i > 0) sql.append(", ");
                sql.append("?");
                args.add(statuses.get(i));
            }
            sql.append(") ");
        }

        sql.append("""
        group by 1
        order by total desc, finished desc, pages desc, author asc
        limit ?
        """);
        args.add(limit);

        return this.jdbc.query(
                sql.toString(),
                args.toArray(),
                (rs, i) -> new TopAuthorStat(
                        rs.getString("author"),
                        rs.getLong("finished"),
                        rs.getLong("abandoned"),
                        rs.getLong("total"),
                        rs.getLong("pages")
                )
        );
    }

    /**
     * Update top_book. If topBook becomes true: set top_book_set_at using MOBILE timestamp,
     * but keep the first-set semantics (coalesce).
     * If topBook becomes false: no timestamp needed.
     */
    @Transactional
    public void applyTopBookWithTimestamp(String id, Boolean topBook, Instant topBookSetAt) {
        if (topBook == null) return;

        if (Boolean.TRUE.equals(topBook)) {
            jdbc.update(
                    """
                    update books
                    set top_book = true,
                        top_book_set_at = coalesce(top_book_set_at, ?)
                    where id = ?::uuid
                    """,
                    Timestamp.from(topBookSetAt),
                    id);
        } else {
            jdbc.update("update books set top_book = false where id = ?::uuid", id);
        }
    }
}