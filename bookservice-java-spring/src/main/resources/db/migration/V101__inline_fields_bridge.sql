-- V101__inline_fields_bridge.sql

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='width_mm'
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='width'
  ) THEN
    ALTER TABLE books RENAME COLUMN width_mm TO width;
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='height_mm'
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='height'
  ) THEN
    ALTER TABLE books RENAME COLUMN height_mm TO height;
  END IF;
END$$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='width'
  ) THEN
    ALTER TABLE books ADD COLUMN width integer;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='height'
  ) THEN
    ALTER TABLE books ADD COLUMN height integer;
  END IF;
END$$;

-- 1) Inline author/publisher (keep normalized ids; app can ignore them)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='author'
  ) THEN
    ALTER TABLE books ADD COLUMN author text;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='publisher'
  ) THEN
    ALTER TABLE books ADD COLUMN publisher text;
  END IF;
END$$;

-- Best-effort populate from authors/publishers.name if present
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='authors' AND column_name='name') THEN
    UPDATE books b SET author = a.name FROM authors a WHERE b.author_id = a.id AND b.author IS NULL;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='publishers' AND column_name='name') THEN
    UPDATE books b SET publisher = p.name FROM publishers p WHERE b.publisher_id = p.id AND b.publisher IS NULL;
  END IF;
END$$;

-- 2) Registered timestamp for ordering (ok if it already exists)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='registered_at'
  ) THEN
    ALTER TABLE books ADD COLUMN registered_at timestamptz NOT NULL DEFAULT now();
    CREATE INDEX IF NOT EXISTS idx_books_registered_at ON books(registered_at);
  END IF;
END$$;

-- 3) Fields your app + V102 rely on
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='pages'
  ) THEN
    ALTER TABLE books ADD COLUMN pages integer;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='reading_status'
  ) THEN
    ALTER TABLE books ADD COLUMN reading_status text NOT NULL DEFAULT 'in_progress';
    ALTER TABLE books ADD CONSTRAINT books_reading_status_check
      CHECK (reading_status IN ('in_progress','finished','abandoned'));
    ALTER TABLE books ADD COLUMN reading_status_updated_at timestamptz;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='top_book'
  ) THEN
    ALTER TABLE books ADD COLUMN top_book boolean NOT NULL DEFAULT false;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='top_book_set_at'
  ) THEN
    ALTER TABLE books ADD COLUMN top_book_set_at timestamptz;
  END IF;
END$$;

-- 4) Helpful indexes (no-ops if already created elsewhere)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname='idx_books_width') THEN
    CREATE INDEX idx_books_width ON books(width);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname='idx_books_height') THEN
    CREATE INDEX idx_books_height ON books(height);
  END IF;
END$$;