create table if not exists book_enrichment_job (
  id bigserial primary key,
  book_id bigint not null,

  status text not null default 'queued', -- queued|processing|done|error
  attempts int not null default 0,

  next_run_at timestamptz not null default now(),
  locked_at timestamptz,
  locked_by text,

  last_error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_bej_status_next on book_enrichment_job(status, next_run_at);
create unique index if not exists uq_bej_book_open
  on book_enrichment_job(book_id)
  where status in ('queued','processing');