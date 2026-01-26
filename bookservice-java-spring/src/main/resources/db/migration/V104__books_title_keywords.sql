-- V104: add inline title keyword columns used by BookDao.insert

ALTER TABLE public.books
  ADD COLUMN IF NOT EXISTS title_keyword              text,
  ADD COLUMN IF NOT EXISTS title_keyword_position     integer,
  ADD COLUMN IF NOT EXISTS title_keyword2             text,
  ADD COLUMN IF NOT EXISTS title_keyword2_position    integer,
  ADD COLUMN IF NOT EXISTS title_keyword3             text,
  ADD COLUMN IF NOT EXISTS title_keyword3_position    integer;