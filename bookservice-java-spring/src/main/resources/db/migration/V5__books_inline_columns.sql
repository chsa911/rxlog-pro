
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

    CREATE TABLE IF NOT EXISTS book_barcodes (
      book_id uuid,
      barcode text NOT NULL
    );

    DO $$
    DECLARE
      col_type text;
    BEGIN
      SELECT data_type INTO col_type
      FROM information_schema.columns
      WHERE table_schema='public' AND table_name='book_barcodes' AND column_name='book_id';

      IF col_type IS NOT NULL AND col_type <> 'uuid' THEN
        -- Drop constraints that might reference the old column shape (best effort)
        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname='book_barcodes_pkey') THEN
          ALTER TABLE book_barcodes DROP CONSTRAINT book_barcodes_pkey;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname='book_barcodes_book_id_fkey') THEN
          ALTER TABLE book_barcodes DROP CONSTRAINT book_barcodes_book_id_fkey;
        END IF;

        ALTER TABLE book_barcodes ADD COLUMN IF NOT EXISTS book_id_uuid uuid;

        UPDATE book_barcodes
          SET book_id_uuid = CASE
            WHEN book_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
              THEN book_id::uuid
            ELSE NULL
          END
        WHERE book_id_uuid IS NULL;

        ALTER TABLE book_barcodes DROP COLUMN book_id;
        ALTER TABLE book_barcodes RENAME COLUMN book_id_uuid TO book_id;
      END IF;
    END$$;

    CREATE UNIQUE INDEX IF NOT EXISTS book_barcodes_book_id_barcode_uq
      ON book_barcodes(book_id, barcode);

    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname='book_barcodes_book_id_fkey'
      ) THEN
        ALTER TABLE book_barcodes
          ADD CONSTRAINT book_barcodes_book_id_fkey
          FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE;
      END IF;
    END$$;

    CREATE INDEX IF NOT EXISTS idx_book_barcodes_barcode ON book_barcodes(barcode);