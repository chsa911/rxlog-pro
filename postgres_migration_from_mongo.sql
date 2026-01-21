-- rxlog: materialize typed tables from mongo_staging (best-effort)
-- Safe to run multiple times; uses UPSERTs or truncates as noted.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1) size_rules (based on migrations V1 + V30)
CREATE TABLE IF NOT EXISTS public.size_rules (
  id              SERIAL PRIMARY KEY,
  name            TEXT,
  description     TEXT,
  min_width_mm    INT,
  max_width_mm    INT,
  min_height_mm   INT DEFAULT 0,
  max_height_mm   INT,
  color_code      TEXT,
  t_height_mm     INT,
  eq_values_mm    INT[],
  gt_exclude_mm   INT[],
  prefix_down     TEXT,
  prefix_left     TEXT,
  prefix_up       TEXT
);

-- 2) barcodes
-- Note: older migration hints mention an enum 'barcode_status', but recent seeds use is_available + position.
CREATE TABLE IF NOT EXISTS public.barcodes (
  code          TEXT PRIMARY KEY,
  is_available  BOOLEAN NOT NULL DEFAULT TRUE,
  size_rule_id  INT REFERENCES public.size_rules(id),
  position      TEXT,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3) books (skeleton based on migrations V4/V5)
-- If you already created a richer books table elsewhere, skip this.
CREATE TABLE IF NOT EXISTS public.books (
  id            TEXT PRIMARY KEY,           -- from Mongo _id (string)
  title         TEXT,
  author        TEXT,
  publisher     TEXT,
  isbn          TEXT,
  width_mm      INT CHECK (width_mm IS NULL OR width_mm >= 1),
  height_mm     INT CHECK (height_mm IS NULL OR height_mm >= 1),
  created_at    TIMESTAMPTZ,
  updated_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_books_title_trgm ON public.books USING GIN (title gin_trgm_ops);

-- ===== Loaders from mongo_staging (run safely if the collections exist) =====

-- Helper: inserts for size_rules
DO $$
BEGIN
  IF to_regclass('mongo_staging.sizerules') IS NOT NULL THEN
    INSERT INTO public.size_rules (id, name, description, min_width_mm, max_width_mm, min_height_mm, max_height_mm,
                                   color_code, t_height_mm, eq_values_mm, gt_exclude_mm, prefix_down, prefix_left, prefix_up)
    SELECT
      NULLIF(raw->>'id','')::int,
      COALESCE(raw->>'name', raw->>'ruleName'),
      raw->>'description',
      NULLIF(raw->>'min_width_mm','')::int,
      NULLIF(raw->>'max_width_mm','')::int,
      NULLIF(COALESCE(raw->>'min_height_mm', raw->>'min_height',''),'')::int,
      NULLIF(COALESCE(raw->>'max_height_mm', raw->>'max_height',''),'')::int,
      raw->>'color_code',
      NULLIF(raw->>'t_height_mm','')::int,
      CASE WHEN jsonb_typeof(raw->'eq_values_mm')='array' THEN
        (SELECT array_agg( (x)::int ) FROM jsonb_array_elements_text(raw->'eq_values_mm') AS t(x))
      ELSE NULL END,
      CASE WHEN jsonb_typeof(raw->'gt_exclude_mm')='array' THEN
        (SELECT array_agg( (x)::int ) FROM jsonb_array_elements_text(raw->'gt_exclude_mm') AS t(x))
      ELSE NULL END,
      raw->>'prefix_down',
      raw->>'prefix_left',
      raw->>'prefix_up'
    FROM mongo_staging.sizerules
    ON CONFLICT (id) DO UPDATE
      SET name = EXCLUDED.name,
          description = EXCLUDED.description,
          min_width_mm = EXCLUDED.min_width_mm,
          max_width_mm = EXCLUDED.max_width_mm,
          min_height_mm = EXCLUDED.min_height_mm,
          max_height_mm = EXCLUDED.max_height_mm,
          color_code = EXCLUDED.color_code,
          t_height_mm = EXCLUDED.t_height_mm,
          eq_values_mm = EXCLUDED.eq_values_mm,
          gt_exclude_mm = EXCLUDED.gt_exclude_mm,
          prefix_down = EXCLUDED.prefix_down,
          prefix_left = EXCLUDED.prefix_left,
          prefix_up = EXCLUDED.prefix_up;
  END IF;
END $$;

-- barcodes
DO $$
BEGIN
  IF to_regclass('mongo_staging.barcodes') IS NOT NULL THEN
    INSERT INTO public.barcodes (code, is_available, size_rule_id, position, updated_at)
    SELECT
      COALESCE(NULLIF(raw->>'code',''), raw->>'_id'),
      CASE
        WHEN raw ? 'is_available' THEN NULLIF(raw->>'is_available','')::boolean
        WHEN raw ? 'isAvailable'  THEN NULLIF(raw->>'isAvailable','')::boolean
        WHEN raw ? 'status'       THEN ((raw->>'status') = 'AVAILABLE')
        ELSE TRUE
      END,
      COALESCE(NULLIF(raw->>'size_rule_id','')::int, NULLIF(raw->>'sizeRuleId','')::int),
      COALESCE(raw->>'position', raw->>'pos'),
      COALESCE(NULLIF(raw->>'updatedAt','')::timestamptz, NULLIF(raw->>'updated_at','')::timestamptz, now())
    FROM mongo_staging.barcodes
    ON CONFLICT (code) DO UPDATE
      SET is_available = EXCLUDED.is_available,
          size_rule_id = EXCLUDED.size_rule_id,
          position     = EXCLUDED.position,
          updated_at   = EXCLUDED.updated_at;
  END IF;
END $$;

-- books
DO $$
BEGIN
  IF to_regclass('mongo_staging.books') IS NOT NULL THEN
    INSERT INTO public.books (id, title, author, publisher, isbn, width_mm, height_mm, created_at, updated_at)
    SELECT
      raw->>'_id',
      raw->>'title',
      COALESCE(raw->>'author', raw->'author'->>'name'),
      COALESCE(raw->>'publisher', raw->'publisher'->>'name'),
      COALESCE(raw->>'isbn', raw->>'ISBN', raw->>'isbn13', raw->>'isbn10'),
      NULLIF(raw->>'width_mm','')::int,
      NULLIF(raw->>'height_mm','')::int,
      NULLIF(raw->>'createdAt','')::timestamptz,
      NULLIF(raw->>'updatedAt','')::timestamptz
    FROM mongo_staging.books
    ON CONFLICT (id) DO UPDATE
      SET title      = EXCLUDED.title,
          author     = EXCLUDED.author,
          publisher  = EXCLUDED.publisher,
          isbn       = EXCLUDED.isbn,
          width_mm   = EXCLUDED.width_mm,
          height_mm  = EXCLUDED.height_mm,
          created_at = EXCLUDED.created_at,
          updated_at = EXCLUDED.updated_at;
  END IF;
END $$;

-- Optional: drop old staging once verified
-- DROP SCHEMA mongo_staging CASCADE;
