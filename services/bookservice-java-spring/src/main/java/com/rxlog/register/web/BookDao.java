package com.rxlog.register.web;

import com.rxlog.register.api.RegisterBookRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** JDBC-basierter Zugriff auf Bücher + Barcodes. */
@Repository
public class BookDao {

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
        boolean freeBarcodes = false;

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
                freeBarcodes = true;
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

        if (freeBarcodes) {
            List<String> codes =
                    this.jdbc.query(
                            "select barcode from book_barcodes where book_id = ?::uuid",
                            (rs, i) -> rs.getString(1),
                            id);

            // usedTo must be reading_status_updated_at (which is set to now() above)
            Timestamp ts =
                    this.jdbc.queryForObject(
                            "select reading_status_updated_at from books where id = ?::uuid",
                            Timestamp.class,
                            id);
            Instant usedTo = ts != null ? ts.toInstant() : Instant.now();

            String reason = req.getReadingStatus();

            for (String code : codes) {
                barcodeClient.usageRelease(code, id, usedTo, reason);
                barcodeClient.release(code);
            }

            this.jdbc.update("delete from book_barcodes where book_id = ?::uuid", id);
            ++updated;

        } else if (req.getBarcodes() != null) {
            // Replace barcodes
            this.jdbc.update("delete from book_barcodes where book_id = ?::uuid", id);

            LinkedHashSet<String> uniq = new LinkedHashSet<>();
            for (String b : req.getBarcodes()) {
                if (b != null) {
                    String norm = BarcodeNormalizer.normalizeOrNull(b);
                    if (norm != null) uniq.add(norm);
                    else throw new IllegalArgumentException("Invalid barcode: " + b);
                }
            }

            // usedFrom should be registered_at (per your rule)
            Timestamp regTs =
                    this.jdbc.queryForObject(
                            "select registered_at from books where id = ?::uuid",
                            Timestamp.class,
                            id);
            Instant usedFrom = regTs != null ? regTs.toInstant() : Instant.now();

            for (String code : uniq) {
                this.jdbc.update(
                        "insert into book_barcodes (book_id, barcode) values (?::uuid, ?)",
                        id,
                        code);
                barcodeClient.usageAssign(code, id, usedFrom);
            }

            ++updated;
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

            // usedFrom must be registered_at
            barcodeClient.usageAssign(code, id, registeredAt);
        }

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

        return id;
    }

    // ------------------------------------------------------------
    // Mobile-sync helpers (write MOBILE timestamps)
    // ------------------------------------------------------------

    /**
     * Update reading_status using a MOBILE timestamp (not now()).
     * If status becomes finished/abandoned: release barcodes + write history.
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
            List<String> codes =
                    this.jdbc.query(
                            "select barcode from book_barcodes where book_id = ?::uuid",
                            (rs, i) -> rs.getString(1),
                            id);

            // usedTo must be reading_status_updated_at (mobile timestamp)
            for (String code : codes) {
                barcodeClient.usageRelease(code, id, changedAt, readingStatus);
                barcodeClient.release(code);
            }

            this.jdbc.update("delete from book_barcodes where book_id = ?::uuid", id);
        }
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