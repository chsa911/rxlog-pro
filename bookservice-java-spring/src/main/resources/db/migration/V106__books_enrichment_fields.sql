alter table public.books
  add column if not exists isbn13 text,
  add column if not exists purchase_source text,
  add column if not exists purchase_url text,
  add column if not exists enrichment_confidence numeric(4,3),
  add column if not exists enrichment_resolved_at timestamptz;

create index if not exists idx_books_isbn13 on public.books(isbn13);