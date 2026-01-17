package com.rxlog.register.publicapi;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class PublicBooksDao {

    public enum Bucket { top, finished, abandoned, registered }

    private final JdbcTemplate jdbc;

    public PublicBooksDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PublicBookRow> search(String author, String title, Bucket bucket, int limit) {
        if (limit <= 0) limit = 50;
        if (limit > 200) limit = 200;
        if (bucket == null) bucket = Bucket.registered;

        // Until full-title exists, show a derived title from keywords:
        String titleExpr = "trim(concat_ws(' ', b.title_keyword, b.title_keyword2, b.title_keyword3))";

        StringBuilder sql = new StringBuilder();
        sql.append("select coalesce(a.full_name, b.author) as author, ")
                .append(titleExpr)
                .append(" as title ")
                .append("from books b ")
                .append("left join author_aliases a on a.abbr_norm = lower(trim(b.author)) ");

        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        if (StringUtils.hasText(author)) {
            // match both expanded and raw author (so searching "a." or "archer" works)
            where.add("(coalesce(a.full_name, b.author) ilike ? or b.author ilike ?)");
            String pat = "%" + author.trim() + "%";
            args.add(pat);
            args.add(pat);
        }

        if (StringUtils.hasText(title)) {
            where.add("(b.title_keyword ilike ? or b.title_keyword2 ilike ? or b.title_keyword3 ilike ?)");
            String pat = "%" + title.trim() + "%";
            args.add(pat);
            args.add(pat);
            args.add(pat);
        }

        switch (bucket) {
            case top -> where.add("b.top_book = true");
            case finished -> where.add("b.reading_status = 'finished'");
            case abandoned -> where.add("b.reading_status = 'abandoned'");
            case registered -> { /* no extra filter */ }
        }

        if (!where.isEmpty()) {
            sql.append("where ").append(String.join(" and ", where)).append(" ");
        }

        switch (bucket) {
            case top -> sql.append("order by b.top_book_set_at desc nulls last, b.id desc ");
            case finished, abandoned -> sql.append("order by b.reading_status_updated_at desc nulls last, b.id desc ");
            case registered -> sql.append("order by b.registered_at desc nulls last, b.id desc ");
        }

        sql.append("limit ? ");
        args.add(limit);

        return jdbc.query(
                sql.toString(),
                args.toArray(),
                (rs, i) -> new PublicBookRow(rs.getString("author"), rs.getString("title"))
        );
    }
}