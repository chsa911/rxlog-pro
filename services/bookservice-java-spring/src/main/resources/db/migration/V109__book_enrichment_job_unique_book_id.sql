-- Needed so BookDao.insert() can use: ON CONFLICT (book_id) DO UPDATE
CREATE UNIQUE INDEX IF NOT EXISTS uq_book_enrichment_job_book_id
  ON book_enrichment_job(book_id);