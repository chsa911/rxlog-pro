-- Persist book dimensions in millimeters
ALTER TABLE books
  ADD COLUMN IF NOT EXISTS width_mm  INT CHECK (width_mm  IS NULL OR width_mm  >= 1),
  ADD COLUMN IF NOT EXISTS height_mm INT CHECK (height_mm IS NULL OR height_mm >= 1);

CREATE INDEX IF NOT EXISTS idx_books_width_mm  ON books(width_mm);
CREATE INDEX IF NOT EXISTS idx_books_height_mm ON books(height_mm);