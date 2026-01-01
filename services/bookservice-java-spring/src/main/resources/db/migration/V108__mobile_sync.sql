-- V108__mobile_sync.sql
-- Stores idempotent receipts + unresolved items for later resolution in the local RxLog app.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS mobile_sync_receipts (
  client_change_id  text PRIMARY KEY,
  status            text NOT NULL,                 -- applied | needs_review | rejected
  book_id           uuid NULL,
  issue_id          uuid NULL,
  error_code        text NULL,
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS mobile_sync_issues (
  issue_id                  uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  client_change_id          text NOT NULL UNIQUE,
  barcode                   text NOT NULL,
  pages                     int NULL,
  reading_status            text NULL,
  reading_status_changed_at timestamptz NULL,
  top_book                  boolean NULL,
  top_book_set_at           timestamptz NULL,
  error_code                text NOT NULL,
  status                    text NOT NULL DEFAULT 'open',   -- open | resolved
  created_at                timestamptz NOT NULL DEFAULT now(),
  resolved_book_id          uuid NULL,
  resolved_at               timestamptz NULL
);

CREATE INDEX IF NOT EXISTS idx_mobile_sync_issues_status_created_at
  ON mobile_sync_issues (status, created_at DESC);