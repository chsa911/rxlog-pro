create table if not exists public.barcodes_history (
  id uuid primary key default public.uuid_generate_v4(),
  book_id uuid not null references books(id) on delete cascade,
  barcode text not null,
  created_at timestamptz not null
);

create index if not exists idx_barcodes_history_created_at
  on public.barcodes_history(created_at desc);

create index if not exists idx_barcodes_history_barcode_created
  on public.barcodes_history(barcode, created_at desc);

create index if not exists idx_barcodes_history_book_created
  on public.barcodes_history(book_id, created_at desc);