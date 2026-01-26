-- Adds optional classification fields:
-- is_fiction (TRUE/FALSE/NULL), genre (broad), sub_genre (specific), themes (free-text)

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='is_fiction'
  ) THEN
    ALTER TABLE books ADD COLUMN is_fiction boolean;
    CREATE INDEX IF NOT EXISTS idx_books_is_fiction ON books(is_fiction);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='genre'
  ) THEN
    ALTER TABLE books ADD COLUMN genre text;
    CREATE INDEX IF NOT EXISTS idx_books_genre ON books(genre);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='sub_genre'
  ) THEN
    ALTER TABLE books ADD COLUMN sub_genre text;
    CREATE INDEX IF NOT EXISTS idx_books_sub_genre ON books(sub_genre);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='books' AND column_name='themes'
  ) THEN
    ALTER TABLE books ADD COLUMN themes text;
  END IF;
END$$;
