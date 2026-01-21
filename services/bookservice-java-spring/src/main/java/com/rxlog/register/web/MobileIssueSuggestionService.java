package com.rxlog.register.web;

import com.rxlog.register.web.MobileIssueSuggestionsResponse.BarcodeGroup;
import com.rxlog.register.web.MobileIssueSuggestionsResponse.BookHint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MobileIssueSuggestionService {

    private final JdbcTemplate jdbc;
    private final ConfusableTokenStore confusables;

    public MobileIssueSuggestionService(JdbcTemplate jdbc, ConfusableTokenStore confusables) {
        this.jdbc = jdbc;
        this.confusables = confusables;
    }

    private record IssueRow(String issueId, String barcode, Integer pages, String errorCode) {}

    public MobileIssueSuggestionsResponse suggestions(String issueId, int limit) {
        IssueRow issue = loadIssue(issueId);

        int samePagesCount = 0;
        List<BookHint> samePages = List.of();
        if (issue.pages != null) {
            samePagesCount = countBooksByPages(issue.pages);
            samePages = findBooksByPages(issue.pages, limit);
        }

        List<BarcodeGroup> confusable = List.of();
        if (issue.barcode != null && !issue.barcode.isBlank()) {
            confusable = findConfusableBarcodeGroups(issue.barcode, limit);
        }

        return new MobileIssueSuggestionsResponse(
                issue.issueId,
                issue.errorCode,
                issue.barcode,
                issue.pages,
                samePagesCount,
                samePages,
                confusable);
    }

    private IssueRow loadIssue(String issueId) {
        try {
            return jdbc.queryForObject(
                    """
                    select issue_id::text, barcode, pages, error_code
                    from mobile_sync_issues
                    where issue_id = ?::uuid
                    """,
                    (rs, i) -> new IssueRow(
                            rs.getString(1),
                            rs.getString(2),
                            (Integer) rs.getObject(3),
                            rs.getString(4)),
                    issueId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found: " + issueId);
        }
    }

    private int countBooksByPages(int pages) {
        Integer n = jdbc.queryForObject("select count(*) from books where pages = ?", Integer.class, pages);
        return n != null ? n : 0;
    }

    private List<BookHint> findBooksByPages(int pages, int limit) {
        String sql =
                """
                select
                  b.id::text as id,
                  b.author,
                  b.title_keyword,
                  b.pages,
                  string_agg(distinct bb.barcode, ',') as barcodes_csv
                from books b
                left join book_barcodes bb on bb.book_id = b.id
                where b.pages = ?
                group by b.id
                order by b.registered_at desc nulls last, b.id desc
                limit ?
                """;

        return jdbc.query(sql, (rs, i) -> mapBookHint(rs), pages, limit);
    }

    private BookHint mapBookHint(ResultSet rs) throws SQLException {
        String csv = rs.getString("barcodes_csv");
        List<String> barcodes = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String p : csv.split(",")) {
                String s = p.trim();
                if (!s.isEmpty()) barcodes.add(s);
            }
        }
        return new BookHint(
                rs.getString("id"),
                rs.getString("author"),
                rs.getString("title_keyword"),
                (Integer) rs.getObject("pages"),
                barcodes);
    }

    private record BarcodeParts(String pos, String token, String suffix) {}

    private BarcodeParts splitNormalizedBarcode(String normalized) {
        if (normalized == null) return null;
        String s = normalized.trim().toLowerCase(Locale.ROOT);
        if (!s.matches("^[dlo][a-z]{1,2}\\d{3}$")) return null;
        String pos = s.substring(0, 1);
        String suffix = s.substring(s.length() - 3);
        String token = s.substring(1, s.length() - 3);
        return new BarcodeParts(pos, token, suffix);
    }

    /**
     * Build candidate barcodes from configured confusable groups.
     * Supports two formats in the config file:
     * - token groups: k,kb,kg  (converted to pos + token + suffix)
     * - full barcode groups: dk010,dkb010,dkg010 (used as-is)
     */
    private List<BarcodeGroup> findConfusableBarcodeGroups(String normalizedBarcode, int limit) {
        BarcodeParts parts = splitNormalizedBarcode(normalizedBarcode);
        if (parts == null) return List.of();

        LinkedHashSet<String> candidateCodes = new LinkedHashSet<>();

        // 1) full-code alternatives
        for (String alt : confusables.alternatives(normalizedBarcode)) {
            String code = BarcodeNormalizer.normalizeOrNull(alt);
            if (code != null) candidateCodes.add(code);
        }

        // 2) token alternatives
        for (String alt : confusables.alternatives(parts.token)) {
            if (alt == null || alt.isBlank()) continue;

            String maybeFull = BarcodeNormalizer.normalizeOrNull(alt);
            if (maybeFull != null) {
                candidateCodes.add(maybeFull);
                continue;
            }

            String code = parts.pos + alt.trim().toLowerCase(Locale.ROOT) + parts.suffix;
            if (BarcodeNormalizer.normalizeOrNull(code) != null) {
                candidateCodes.add(code);
            }
        }

        candidateCodes.remove(normalizedBarcode.toLowerCase(Locale.ROOT));
        if (candidateCodes.isEmpty()) return List.of();

        List<String> codes = new ArrayList<>(candidateCodes);
        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));

        String sql =
                """
                select
                  bb.barcode as match_barcode,
                  b.id::text as book_id,
                  b.author,
                  b.title_keyword,
                  b.pages
                from book_barcodes bb
                join books b on b.id = bb.book_id
                where bb.barcode in (""" + placeholders + ")\n" +
                "order by bb.barcode asc, b.registered_at desc nulls last, b.id desc\n";

        List<Object> args = new ArrayList<>(codes);

        List<Row> rows = jdbc.query(sql, args.toArray(), (rs, i) ->
                new Row(
                        rs.getString("match_barcode"),
                        rs.getString("book_id"),
                        rs.getString("author"),
                        rs.getString("title_keyword"),
                        (Integer) rs.getObject("pages")));

        Map<String, List<BookHint>> grouped = new LinkedHashMap<>();
        for (String c : codes) grouped.put(c, new ArrayList<>());

        for (Row r : rows) {
            grouped.computeIfAbsent(r.matchBarcode, _k -> new ArrayList<>())
                    .add(new BookHint(r.bookId, r.author, r.titleKeyword, r.pages, List.of(r.matchBarcode)));
        }

        List<BarcodeGroup> out = new ArrayList<>();
        for (var e : grouped.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            out.add(new BarcodeGroup(e.getKey(), e.getValue()));
            if (out.size() >= limit) break;
        }

        return out;
    }

    private record Row(String matchBarcode, String bookId, String author, String titleKeyword, Integer pages) {}
}
