#!/usr/bin/env python3
import argparse
import csv
import json
import math
import os
import random
import re
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

import pandas as pd
import requests

try:
    from rapidfuzz import fuzz
except Exception:
    fuzz = None

OPENLIB_SEARCH = "https://openlibrary.org/search.json"

# Abbrev token (leave it in data, but don't use as search constraint)
ABBR_RE = re.compile(r"^[A-Za-zÄÖÜäöüß]{1,4}\.$")

# Bump this if you change what you fetch/store, so old cache entries don't poison new runs
CACHE_VERSION = "v2"

OUT_FIELDS = ["id", "isbn13", "isbn10", "full_title", "match_source", "match_confidence"]


def norm(s: Any) -> str:
    if s is None:
        return ""
    if isinstance(s, float) and math.isnan(s):
        return ""
    s = str(s).strip()
    s = re.sub(r"\s+", " ", s)
    return s


def nlow(s: Any) -> str:
    return norm(s).lower()


def clean_isbn(x: Any) -> Optional[str]:
    if x is None:
        return None
    x = re.sub(r"[^0-9Xx]", "", str(x)).upper()
    if len(x) in (10, 13):
        return x
    return None


def pick_isbns(isbn_list: List[Any]) -> Tuple[str, str]:
    if not isbn_list:
        return "", ""
    cleaned = [clean_isbn(x) for x in isbn_list]
    cleaned = [x for x in cleaned if x]
    isbn13 = next((x for x in cleaned if len(x) == 13), "")
    isbn10 = next((x for x in cleaned if len(x) == 10), "")
    return isbn13, isbn10


def token_score(a: str, b: str) -> int:
    a, b = nlow(a), nlow(b)
    if not a or not b:
        return 0
    if fuzz:
        return int(fuzz.token_set_ratio(a, b))
    return 100 if a in b or b in a else 0


def score_candidate(q_title: str, q_author: str, q_pub: str,
                    cand_title: str, cand_author: str, cand_pub: str) -> float:
    # Ignore abbrev tokens as constraints in scoring too
    q_author = "" if ABBR_RE.match(norm(q_author)) else q_author
    q_pub = "" if ABBR_RE.match(norm(q_pub)) else q_pub

    st = token_score(q_title, cand_title)
    sa = token_score(q_author, cand_author) if q_author and cand_author else 0
    sp = token_score(q_pub, cand_pub) if q_pub and cand_pub else 0

    return 0.75 * st + 0.20 * sa + 0.05 * sp


def make_session() -> requests.Session:
    s = requests.Session()
    s.headers.update({
        "User-Agent": "rxlog-openlibrary-enricher/1.2",
        "Accept": "application/json",
    })
    return s


def openlibrary_search(session: requests.Session,
                       title_kw: str, author: str, publisher: str,
                       timeout_connect: float, timeout_read: float) -> List[Dict[str, Any]]:
    params = {
        "title": title_kw or "",
        "author": author or "",
        "publisher": publisher or "",
        "limit": 10,
        # IMPORTANT: explicitly request isbn (and only the fields we need)
        "fields": "title,subtitle,author_name,publisher,isbn",
    }
    r = session.get(OPENLIB_SEARCH, params=params, timeout=(timeout_connect, timeout_read))
    r.raise_for_status()
    docs = r.json().get("docs", []) or []

    out = []
    for d in docs:
        title = d.get("title") or ""
        subtitle = d.get("subtitle") or ""
        full_title = f"{title}: {subtitle}".strip(": ") if subtitle else title

        a = (d.get("author_name") or [""])[0]
        p = (d.get("publisher") or [""])[0]
        isbns = d.get("isbn") or []

        out.append({
            "full_title": full_title,
            "author": a,
            "publisher": p,
            "isbns": isbns,
            "source": "openlibrary",
        })
    return out


def best_match_openlibrary(session: requests.Session,
                           title_kw: str, author_q: str, publisher_q: str,
                           timeout_connect: float, timeout_read: float,
                           max_attempts: int) -> Tuple[str, str, str, str, float]:
    candidates: List[Dict[str, Any]] = []

    for attempt in range(max_attempts):
        try:
            candidates = openlibrary_search(
                session, title_kw, author_q, publisher_q,
                timeout_connect, timeout_read
            )
            break
        except Exception:
            # tiny backoff with jitter
            time.sleep(min(1.5, 0.25 * (2 ** attempt)) + random.random() * 0.1)

    if not candidates:
        return "", "", "", "openlibrary", 0.0

    best = None
    best_score = -1.0
    for c in candidates:
        s = score_candidate(title_kw, author_q, publisher_q, c["full_title"], c["author"], c["publisher"])
        if c.get("isbns"):
            s += 2.0
        if s > best_score:
            best_score = s
            best = c

    assert best is not None
    isbn13, isbn10 = pick_isbns(best.get("isbns") or [])
    conf = max(0.0, min(1.0, best_score / 100.0))
    return best.get("full_title", "") or "", isbn13, isbn10, "openlibrary", round(conf, 3)


def load_json(path: str) -> Dict[str, Any]:
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_json(path: str, data: Dict[str, Any]) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f)
    os.replace(tmp, path)


def ensure_output_has_header(path: str) -> None:
    if os.path.exists(path):
        return
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=OUT_FIELDS)
        w.writeheader()


def read_done_ids(output_csv: str) -> set:
    if not os.path.exists(output_csv):
        return set()
    done = set()
    with open(output_csv, "r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        if "id" not in (reader.fieldnames or []):
            return set()
        for row in reader:
            rid = (row.get("id") or "").strip()
            if rid:
                done.add(rid)
    return done


def append_rows(output_csv: str, rows: List[Dict[str, Any]]) -> None:
    if not rows:
        return
    ensure_output_has_header(output_csv)
    with open(output_csv, "a", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=OUT_FIELDS)
        writer.writerows(rows)


def main() -> int:
    ap = argparse.ArgumentParser(description="Enrich books with ISBN + full title (Open Library only).")
    ap.add_argument("--input", default="books_seed_expanded.csv")
    ap.add_argument("--output", default="books_enriched.csv")
    ap.add_argument("--cache", default="enrich_cache.json")
    ap.add_argument("--chunk", type=int, default=20)
    ap.add_argument("--start-over", action="store_true")
    ap.add_argument("--sleep", type=float, default=0.0)
    ap.add_argument("--timeout-connect", type=float, default=5.0)
    ap.add_argument("--timeout-read", type=float, default=10.0)
    ap.add_argument("--max-attempts", type=int, default=1)
    ap.add_argument("--log-every", type=int, default=25)
    args = ap.parse_args()

    if args.start_over:
        if os.path.exists(args.output):
            os.remove(args.output)
        if os.path.exists(args.cache):
            os.remove(args.cache)

    if not os.path.exists(args.input):
        print(f"Input not found: {args.input}", file=sys.stderr)
        return 2

    df = pd.read_csv(args.input, dtype=str).fillna("")
    for col in ["id", "author", "publisher", "titlekeyword"]:
        if col not in df.columns:
            print(f"Missing required column: {col}", file=sys.stderr)
            return 2

    ensure_output_has_header(args.output)

    done_ids = read_done_ids(args.output)
    cache: Dict[str, Any] = load_json(args.cache)
    session = make_session()

    out_rows: List[Dict[str, Any]] = []
    total = len(df)
    new = 0
    skipped = 0

    for idx, row in df.iterrows():
        rid = (row.get("id") or "").strip()
        if not rid:
            continue

        if rid in done_ids:
            skipped += 1
            continue

        title_kw = norm(row.get("titlekeyword", ""))
        author = norm(row.get("author", ""))
        publisher = norm(row.get("publisher", ""))

        # Ignore abbrev-only author/publisher as search constraints (but keep in your data)
        author_q = "" if ABBR_RE.match(author) else author
        publisher_q = "" if ABBR_RE.match(publisher) else publisher

        # Versioned cache key so old cached "no isbn" results won't be reused
        cache_key = f"{CACHE_VERSION}|{nlow(author_q)}||{nlow(publisher_q)}||{nlow(title_kw)}"

        if cache_key in cache:
            c = cache[cache_key]
            full_title = c.get("full_title", "") or ""
            isbn13 = c.get("isbn13", "") or ""
            isbn10 = c.get("isbn10", "") or ""
            source = c.get("match_source", "openlibrary") or "openlibrary"
            conf = c.get("match_confidence", 0.0) or 0.0
        else:
            full_title, isbn13, isbn10, source, conf = best_match_openlibrary(
                session, title_kw, author_q, publisher_q,
                args.timeout_connect, args.timeout_read, args.max_attempts
            )
            cache[cache_key] = {
                "full_title": full_title,
                "isbn13": isbn13,
                "isbn10": isbn10,
                "match_source": source,
                "match_confidence": conf,
            }
            if args.sleep > 0:
                time.sleep(args.sleep)

        out_rows.append({
            "id": rid,
            "isbn13": isbn13,
            "isbn10": isbn10,
            "full_title": full_title,
            "match_source": source,
            "match_confidence": conf,
        })
        new += 1

        if len(out_rows) >= args.chunk:
            append_rows(args.output, out_rows)
            out_rows = []
            save_json(args.cache, cache)
            done_ids = read_done_ids(args.output)

        if (idx + 1) % args.log_every == 0:
            print(f"[{idx+1}/{total}] new={new} skipped={skipped} cache={len(cache)}", file=sys.stderr)

    append_rows(args.output, out_rows)
    save_json(args.cache, cache)

    print(f"Done. Wrote/updated: {args.output}")
    print(f"New rows: {new} | Skipped: {skipped} | Cache entries: {len(cache)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())