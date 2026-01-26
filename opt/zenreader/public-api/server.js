import express from "express";
import pg from "pg";

const { Pool } = pg;
const app = express();

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
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

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`public-api listening on ${port}`));