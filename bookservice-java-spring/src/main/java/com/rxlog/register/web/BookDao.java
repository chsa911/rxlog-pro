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
        r.setIsFiction((Boolean) rs.getObject("is_fiction"));
        r.setGenre(rs.getString("genre"));
        r.setSubGenre(rs.getString("sub_genre"));
        r.setThemes(rs.getString("themes"));
        r.setWidth((Integer) rs.getObject("width"));
        r.setHeight((Integer) rs.getObject("height"));
        String csv = rs.getString("barcodes_csv");
        if (csv != null && !csv.isBlank()) {
            String[] parts = csv.split(",");
            List<String> list = new ArrayList<>();
            for (String p : parts) {
                String s = p.trim();
                if (!s.isEmpty()) {
                    list.add(s);
                }
            }
            r.setBarcodes(list);
        } else {
            r.setBarcodes(Collections.emptyList());
        }
        return r;
    }

    // ------------------------------------------------------------
    // Search (for the Admin-UI)
    // ------------------------------------------------------------

    // ✅ Backwards-compatible overload (old callers)
    public List<BookSearchResult> search(
            String author,
            String publisher,
            String titleLike,
            String barcode,
            String readingStatus,
            Boolean topBook,
            int limit) {

        return search(
                author,
                publisher,
                titleLike,
                barcode,
                readingStatus,
                topBook,
                null,   // isFiction
                false,  // onlyUnspecifiedFiction
                null,   // genre
                null,   // subGenre
                null,   // theme
                limit
        );
    }

    public List<BookSearchResult> search(
            String author,
            String publisher,
            String titleLike,
            String barcode,
            String readingStatus,
            Boolean topBook,
            Boolean isFiction,
            boolean onlyUnspecifiedFiction,
            String genre,
            String subGenre,
            String theme,
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
                              b.is_fiction,
                              b.genre,
                              b.sub_genre,
                              b.themes,
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
            where.add(
                    "(b.title_keyword ilike ? or b.title_keyword2 ilike ? or b.title_keyword3 ilike ?)");
            String pat = "%" + titleLike.trim() + "%";
            args.add(pat);
            args.add(pat);
            args.add(pat);
        }
        if (StringUtils.hasText(barcode)) {
            String code = BarcodeNormalizer.normalizeOrNull(barcode);
            if (code == null) {
                // barcode filter was requested but invalid -> no match
                return Collections.emptyList();
            }
            where.add("""
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

        if (onlyUnspecifiedFiction) {
            where.add("b.is_fiction is null");
        } else if (isFiction != null) {
            where.add("b.is_fiction = ?");
            args.add(isFiction);
        }

        if (StringUtils.hasText(genre)) {
            where.add("b.genre ilike ?");
            args.add("%" + genre.trim() + "%");
        }

        if (StringUtils.hasText(subGenre)) {
            where.add("b.sub_genre ilike ?");
            args.add("%" + subGenre.trim() + "%");
        }

        if (StringUtils.hasText(theme)) {
            where.add("b.themes ilike ?");
            args.add("%" + theme.trim() + "%");
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
    // Analytics (Admin-UI)
    // ------------------------------------------------------------
    public List<TopAuthorStat> topAuthors(List<String> statuses, int limit) {

        StringBuilder sql = new StringBuilder(
                "select b.author as author, count(*)::int as cnt from books b");

        List<Object> args = new ArrayList<>();
        List<String> where = new ArrayList<>();

        where.add("b.author is not null");
        where.add("b.author <> ''");

        if (statuses != null && !statuses.isEmpty()) {
            String placeholders = String.join(", ", Collections.nCopies(statuses.size(), "?"));
            where.add("b.reading_status in (" + placeholders + ")");
            args.addAll(statuses);
        }

        if (!where.isEmpty()) {
            sql.append(" where ").append(String.join(" and ", where));
        }

        sql.append(" group by b.author order by cnt desc, b.author asc limit ?");
        args.add(limit);

        return this.jdbc.query(
                sql.toString(),
                args.toArray(),
                (rs, i) -> createTopAuthorStat(rs.getString("author"), rs.getInt("cnt"))
        );
    }

    /**
     * Create TopAuthorStat whether it's a record (TopAuthorStat(String,int))
     * or a bean with setters (setAuthor/setCount).
     */
    private static TopAuthorStat createTopAuthorStat(String author, int cnt) {
        try {
            Class<TopAuthorStat> c = TopAuthorStat.class;

            // record TopAuthorStat(String author, int count)
            if (c.isRecord()) {
                return c.getDeclaredConstructor(String.class, int.class).newInstance(author, cnt);
            }

            // bean-style: new TopAuthorStat(); setAuthor(...); setCount(...)
            TopAuthorStat obj = c.getDeclaredConstructor().newInstance();

            // try setters
            boolean okAuthor = tryInvokeSetter(obj, "setAuthor", String.class, author)
                    || trySetField(obj, "author", author);

            boolean okCount = tryInvokeSetter(obj, "setCount", int.class, cnt)
                    || tryInvokeSetter(obj, "setCnt", int.class, cnt)
                    || tryInvokeSetter(obj, "setTotal", int.class, cnt)
                    || tryInvokeSetter(obj, "setBooks", int.class, cnt)
                    || tryInvokeSetter(obj, "setNumBooks", int.class, cnt)
                    || trySetField(obj, "count", cnt)
                    || trySetField(obj, "cnt", cnt)
                    || trySetField(obj, "total", cnt);

            if (!okAuthor || !okCount) {
                // still return it; but warn by throwing if you want strictness
                // throw new IllegalStateException("TopAuthorStat has unexpected shape");
            }

            return obj;

        } catch (Exception e) {
            throw new IllegalStateException("Cannot create TopAuthorStat(author=" + author + ", cnt=" + cnt + ")", e);
        }
    }

    private static boolean tryInvokeSetter(Object obj, String method, Class<?> argType, Object value) {
        try {
            obj.getClass().getMethod(method, argType).invoke(obj, value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean trySetField(Object obj, String fieldName, Object value) {
        try {
            var f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------
    // Partial-Update (Admin-UI)
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
            // If status becomes finished or abandoned, we will erase all barcodes for this book
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

        // --- classification fields (optional) ---------------------

        if (req.isIsFictionPresent()) {
            sets.add("is_fiction = ?");
            args.add(req.getIsFiction());
        }

        if (req.isGenrePresent()) {
            sets.add("genre = ?");
            args.add(norm(req.getGenre()));
        }

        if (req.isSubGenrePresent()) {
            sets.add("sub_genre = ?");
            args.add(norm(req.getSubGenre()));
        }

        if (req.isThemesPresent()) {
            sets.add("themes = ?");
            args.add(norm(req.getThemes()));
        }

        int updated = 0;
        if (!sets.isEmpty()) {
            String sql = "update books set " + String.join(", ", sets) + " where id = ?::uuid";
            args.add(id);
            updated = this.jdbc.update(sql, args.toArray());
        }

        // --- Barcode handling ------------------------------------

        if (freeBarcodes) {
            // 1) Load all barcodes currently attached to this book
            List<String> codes =
                    this.jdbc.query(
                            "select barcode from book_barcodes where book_id = ?::uuid",
                            (rs, i) -> rs.getString(1),
                            id);

            // 2) Tell the barcode service to release them (update in-memory used set)
            for (String code : codes) {
                barcodeClient.release(code);
            }

            // 3) Delete them from DB so they are not considered used on restart
            this.jdbc.update("delete from book_barcodes where book_id = ?::uuid", id);
            ++updated;

        } else if (req.getBarcodes() != null) {
            // Replace barcodes if explicitly provided in the request
            this.jdbc.update("delete from book_barcodes where book_id = ?::uuid", id);

            LinkedHashSet<String> uniq = new LinkedHashSet<>();
            for (String b : req.getBarcodes()) {
                if (b != null) {
                    String norm = BarcodeNormalizer.normalizeOrNull(b);
                    if (norm != null) {
                        uniq.add(norm);
                    } else {
                        throw new IllegalArgumentException("Invalid barcode: " + b);
                    }
                }
            }

            for (String code : uniq) {
                this.jdbc.update(
                        "insert into book_barcodes (book_id, barcode) values (?::uuid, ?)", id, code);
            }

            ++updated;
        }

        return updated > 0;
    }

    // ------------------------------------------------------------
    // Insert for Registration
    // ------------------------------------------------------------

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
                            is_fiction,
                            genre,
                            sub_genre,
                            themes,
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
                            ?, ?, ?, ?,
                            now(),
                            now()
                        )
                        returning id::text
                        """;

        String id =
                jdbc.queryForObject(
                        sql,
                        new Object[] {
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
                                top,
                                req.isFiction(),
                                norm(req.genre()),
                                norm(req.subGenre()),
                                norm(req.themes())
                        },
                        String.class);

        if (id == null) {
            throw new IllegalStateException("Insert returned null id");
        }

        if (req.barcode() != null && !req.barcode().isBlank()) {
            String code = BarcodeNormalizer.normalizeOrThrow(req.barcode());
            jdbc.update(
                    "insert into book_barcodes (book_id, barcode) values (?::uuid, ?)",
                    id,
                    code);
        }
        // ------------------------------------------------------------
        // Enqueue enrichment job (ISBN lookup + purchase link) for worker
        // Requires: book_enrichment_job(book_id uuid UNIQUE, ...)
        // ------------------------------------------------------------
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

    private static String norm(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    // ------------------------------------------------------------
    // Mobile-sync helpers (write MOBILE timestamps)
    // ------------------------------------------------------------

    /**
     * Update reading_status using a MOBILE timestamp (not now()).
     * If status becomes finished/abandoned, free barcodes (same behavior as admin partialUpdate).
     */
    @Transactional
    public void applyReadingStatusWithTimestamp(String id, String readingStatus, Instant changedAt) {
        jdbc.update(
                "update books set reading_status = ?, reading_status_updated_at = ? where id = ?::uuid",
                readingStatus,
                Timestamp.from(changedAt),
                id);

        if ("finished".equals(readingStatus) || "abandoned".equals(readingStatus)) {
            // 1) Load all barcodes currently attached to this book
            List<String> codes =
                    this.jdbc.query(
                            "select barcode from book_barcodes where book_id = ?::uuid",
                            (rs, i) -> rs.getString(1),
                            id);

            // 2) Tell the barcode service to release them (update in-memory used set)
            for (String code : codes) {
                barcodeClient.release(code);
            }

            // 3) Delete them from DB so they are not considered used on restart
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