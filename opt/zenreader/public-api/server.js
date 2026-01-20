import express from "express";
import pg from "pg";

const { Pool } = pg;
const app = express();

const pool = new Pool({
  // In some local setups the DATABASE_URL may accidentally contain whitespace (e.g. before '?sslmode=...').
  // pg's connection-string parser is strict, so we normalise it here.
  connectionString: (process.env.DATABASE_URL || "").trim().replace(/\s+/g, ""),
  // Neon: minimal & pragmatisch
  ssl: { rejectUnauthorized: false },
});

app.get("/health", (_req, res) => res.json({ ok: true }));

app.get("/api/public/books", async (req, res) => {
  try {
    const bucket = (req.query.bucket || "registered").toString();
    const authorQ = (req.query.author || "").toString().trim();
    const titleQ = (req.query.title || "").toString().trim();

    let limit = parseInt((req.query.limit || "50").toString(), 10);
    if (!Number.isFinite(limit) || limit <= 0) limit = 50;
    if (limit > 200) limit = 200;

    const where = [];
    const params = [];
    let p = 1;

    if (authorQ) {
      where.push(`author_display ILIKE $${p++}`);
      params.push(`%${authorQ}%`);
    }
    if (titleQ) {
      where.push(`full_title ILIKE $${p++}`);
      params.push(`%${titleQ}%`);
    }

    let orderBy = `registered_at DESC NULLS LAST`;

    if (bucket === "top") {
      where.push(`top_book = true`);
      orderBy = `top_book_set_at DESC NULLS LAST`;
    } else if (bucket === "finished") {
      where.push(`reading_status = 'finished'`);
      orderBy = `reading_status_updated_at DESC NULLS LAST`;
    } else if (bucket === "abandoned") {
      where.push(`reading_status = 'abandoned'`);
      orderBy = `reading_status_updated_at DESC NULLS LAST`;
    } // else: registered

    params.push(limit);
    const limitParam = `$${p++}`;

    const sql = `
      SELECT
        author_display AS author,
        full_title     AS title
      FROM public.books
      ${where.length ? "WHERE " + where.join(" AND ") : ""}
      ORDER BY ${orderBy}
      LIMIT ${limitParam};
    `;

    const { rows } = await pool.query(sql, params);
    res.json(rows);
  } catch (e) {
    res.status(500).json({ error: String(e?.message || e) });
  }
});

// Yearly stats (finished/abandoned/top/registered) derived from Postgres.
// Used by the public books page to show a small dashboard.
app.get("/api/public/books/stats", async (req, res) => {
  try {
    const nowYear = new Date().getUTCFullYear();
    const year = Number.parseInt((req.query.year || String(nowYear)).toString(), 10);
    if (!Number.isFinite(year) || year < 1900 || year > 3000) {
      return res.status(400).json({ error: "Invalid year" });
    }

    const start = new Date(Date.UTC(year, 0, 1, 0, 0, 0)).toISOString();
    const end = new Date(Date.UTC(year + 1, 0, 1, 0, 0, 0)).toISOString();

    const sql = `
      SELECT
        COUNT(*) FILTER (WHERE registered_at >= $1 AND registered_at < $2) AS registered,
        COUNT(*) FILTER (
          WHERE reading_status = 'finished'
            AND reading_status_updated_at >= $1 AND reading_status_updated_at < $2
        ) AS finished,
        COUNT(*) FILTER (
          WHERE reading_status = 'abandoned'
            AND reading_status_updated_at >= $1 AND reading_status_updated_at < $2
        ) AS abandoned,
        COUNT(*) FILTER (
          WHERE top_book = true
            AND top_book_set_at >= $1 AND top_book_set_at < $2
        ) AS top
      FROM public.books;
    `;

    const { rows } = await pool.query(sql, [start, end]);
    const r = rows?.[0] || {};

    // pg returns COUNT() as text by default.
    const toInt = (v) => (v === null || v === undefined ? 0 : Number.parseInt(String(v), 10) || 0);

    res.json({
      year,
      registered: toInt(r.registered),
      finished: toInt(r.finished),
      abandoned: toInt(r.abandoned),
      top: toInt(r.top),
    });
  } catch (e) {
    res.status(500).json({ error: String(e?.message || e) });
  }
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`public-api listening on ${port}`));