package com.rxlog.register.service;

import com.rxlog.register.dto.BookEnrichmentInput;
import com.rxlog.register.dto.BookEnrichmentPatch;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookEnrichmentService {

    private final JdbcTemplate jdbc;

    public BookEnrichmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public BookEnrichmentInput getEnrichmentInput(String bookId) {
        Map<String, Object> r;
        try {
            r = jdbc.queryForMap(
                    """
                    select
                      author, publisher, isbn13,
                      title_keyword, title_keyword_position,
                      title_keyword2, title_keyword2_position,
                      title_keyword3, title_keyword3_position
                    from books
                    where id = ?::uuid
                    """,
                    bookId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Book not found: " + bookId);
        }

        List<BookEnrichmentInput.TitleKeyword> kws = new ArrayList<>();
        addKw(kws, (Integer) r.get("title_keyword_position"), (String) r.get("title_keyword"), 1);
        addKw(kws, (Integer) r.get("title_keyword2_position"), (String) r.get("title_keyword2"), 2);
        addKw(kws, (Integer) r.get("title_keyword3_position"), (String) r.get("title_keyword3"), 3);
        kws.sort(Comparator.comparingInt(BookEnrichmentInput.TitleKeyword::pos));

        return new BookEnrichmentInput(
                bookId,
                (String) r.get("author"),
                (String) r.get("publisher"),
                (String) r.get("isbn13"),
                kws);
    }

    @Transactional
    public void applyPatch(String bookId, BookEnrichmentPatch patch) {
        Map<String, Object> cur;
        try {
            cur = jdbc.queryForMap(
                    """
                    select
                      isbn13,
                      purchase_source,
                      purchase_url,
                      enrichment_confidence,
                      enrichment_resolved_at,
                      year_first_published
                    from books
                    where id = ?::uuid
                    for update
                    """,
                    bookId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Book not found: " + bookId);
        }

        boolean force = patch.force();

        String curIsbn = (String) cur.get("isbn13");
        String curSrc = (String) cur.get("purchase_source");
        String curUrl = (String) cur.get("purchase_url");
        Object curConf = cur.get("enrichment_confidence");
        Object curResAt = cur.get("enrichment_resolved_at");
        Object curYearFirstPublished = cur.get("year_first_published");

        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (patch.isbn13() != null && (force || curIsbn == null)) {
            sets.add("isbn13 = ?");
            args.add(normalizeIsbn(patch.isbn13()));
        }
        if (patch.purchaseSource() != null && (force || curSrc == null)) {
            sets.add("purchase_source = ?");
            args.add(patch.purchaseSource());
        }
        if (patch.purchaseUrl() != null && (force || curUrl == null)) {
            sets.add("purchase_url = ?");
            args.add(patch.purchaseUrl());
        }
        if (patch.confidence() != null && (force || curConf == null)) {
            sets.add("enrichment_confidence = ?");
            args.add(patch.confidence());
        }

        // Use existing column: year_first_published (fill-only-if-null unless force=true)
        if (patch.firstPublishYear() != null && (force || curYearFirstPublished == null)) {
            sets.add("year_first_published = ?");
            args.add(patch.firstPublishYear());
        }

        Instant resolved = patch.resolvedAt() != null ? patch.resolvedAt() : Instant.now();
        if (!sets.isEmpty() && (force || curResAt == null)) {
            sets.add("enrichment_resolved_at = ?");
            args.add(java.sql.Timestamp.from(resolved));
        }

        if (sets.isEmpty()) return;

        String sql = "update books set " + String.join(", ", sets) + " where id = ?::uuid";
        args.add(bookId);
        jdbc.update(sql, args.toArray());
    }

    private static void addKw(List<BookEnrichmentInput.TitleKeyword> out, Integer pos, String kw, int fallback) {
        if (kw == null || kw.isBlank()) return;
        int p = (pos != null) ? pos : 1000 + fallback;
        out.add(new BookEnrichmentInput.TitleKeyword(p, kw));
    }

    private static String normalizeIsbn(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }
}