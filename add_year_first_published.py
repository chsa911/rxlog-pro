#!/usr/bin/env python3
import json
import math
import os
import random
import re
import time
from typing import Any, Dict, List, Optional, Tuple

import pandas as pd
import requests

try:
    from rapidfuzz import fuzz
except Exception:
    fuzz = None

OPENLIB_SEARCH = "https://openlibrary.org/search.json"
GOOGLE_BOOKS = "https://www.googleapis.com/books/v1/volumes"
CACHE_PATH = "year_cache.json"


def norm(s: Any) -> str:
    if s is None:
        return ""
    if isinstance(s, float) and math.isnan(s):
        return ""
    s = str(s).strip().lower()
    s = re.sub(r"\s+", " ", s)
    return s


def parse_year(x: Any) -> str:
    if x is None:
        return ""
    m = re.search(r"(1[5-9]\d{2}|20\d{2})", str(x))
    return m.group(1) if m else ""


def score_candidate(q_title: str, q_author: str, q_pub: str,
                    cand_title: str, cand_author: str, cand_pub: str) -> float:
    qt, qa, qp = norm(q_title), norm(q_author), norm(q_pub)
    ct, ca, cp = norm(cand_title), norm(cand_author), norm(cand_pub)

    if fuzz:
        st = fuzz.token_set_ratio(qt, ct)
        sa = fuzz.token_set_ratio(qa, ca) if qa and ca else 0
        sp = fuzz.token_set_ratio(qp, cp) if qp and cp else 0
    else:
        st = 100 if qt and qt in ct else 0
        sa = 100 if qa and qa in ca else 0
        sp = 100 if qp and qp in cp else 0

    return 0.70 * st + 0.25 * sa + 0.05 * sp


def backoff_sleep(attempt: int) -> None:
    time.sleep(min(10.0, (2 ** attempt) * 0.6) + random.random() * 0.25)


def load_cache() -> Dict[str, Any]:
    if os.path.exists(CACHE_PATH):
        with open(CACHE_PATH, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_cache(cache: Dict[str, Any]) -> None:
    tmp = CACHE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f)
    os.replace(tmp, CACHE_PATH)


def openlibrary_year(session: requests.Session, title_kw: str, author: str, publisher: str) -> Optional[str]:
    params = {"title": title_kw or "", "author": author or "", "publisher": publisher or "", "limit": 10}
    r = session.get(OPENLIB_SEARCH, params=params, timeout=20)
    r.raise_for_status()
    docs = r.json().get("docs", []) or []
    if not docs:
        return None

    best_year = None
    best_score = -1.0

    for d in docs:
        title = d.get("title") or d.get("title_suggest") or ""
        a = (d.get("author_name") or [""])[0]
        p = (d.get("publisher") or [""])[0]
        y = d.get("first_publish_year")

        s = score_candidate(title_kw, author, publisher, title, a, p)
        if y:
            s += 1.0  # small bonus if year exists

        if s > best_score:
            best_score = s
            best_year = str(y) if y else None

    return best_year


def googlebooks_year(session: requests.Session, title_kw: str, author: str, publisher: str) -> Optional[str]:
    q_parts = []
    if title_kw: q_parts.append(f"intitle:{title_kw}")
    if author: q_parts.append(f"inauthor:{author}")
    if publisher: q_parts.append(f"inpublisher:{publisher}")
    q = " ".join(q_parts) if q_parts else (title_kw or "")

    params = {"q": q, "maxResults": 5, "printType": "books"}
    api_key = os.getenv("GOOGLE_BOOKS_API_KEY", "").strip()
    if api_key:
        params["key"] = api_key

    r = session.get(GOOGLE_BOOKS, params=params, timeout=20)
    r.raise_for_status()
    items = r.json().get("items", []) or []
    if not items:
        return None

    best_year = None
    best_score = -1.0

    for it in items:
        vi = it.get("volumeInfo", {}) or {}
        title = vi.get("title", "") or ""
        subtitle = vi.get("subtitle", "") or ""
        full_title = f"{title}: {subtitle}".strip(": ") if subtitle else title
        authors = ", ".join(vi.get("authors") or [])
        pub = vi.get("publisher") or ""
        y = parse_year(vi.get("publishedDate", ""))

        s = score_candidate(title_kw, author, publisher, full_title, authors, pub)
        if y:
            s += 1.0

        if s > best_score:
            best_score = s
            best_year = y if y else None

    return best_year


def main():
    seed_path = "books_seed.csv"
    enr_path = "books_enriched.csv"
    out_path = "books_enriched_with_year.csv"

    seed = pd.read_csv(seed_path, dtype=str).fillna("")
    enr = pd.read_csv(enr_path, dtype=str).fillna("")
    df = seed.merge(enr, on="id", how="left")

    # If year column already exists, keep it; else create
    if "year_first_published" not in df.columns:
        df["year_first_published"] = ""

    session = requests.Session()
    session.headers.update({"User-Agent": "rxlog-year-enricher/1.0"})

    cache = load_cache()

    for i, row in df.iterrows():
        if str(row.get("year_first_published", "")).strip():
            continue

        title_kw = row.get("titlekeyword", "")
        author = row.get("author", "")
        publisher = row.get("publisher", "")

        cache_key = f"{norm(author)}||{norm(publisher)}||{norm(title_kw)}"
        if cache_key in cache:
            df.at[i, "year_first_published"] = cache[cache_key] or ""
            continue

        y = None

        # Open Library first
        for attempt in range(4):
            try:
                y = openlibrary_year(session, title_kw, author, publisher)
                break
            except Exception:
                backoff_sleep(attempt)

        # Fallback Google Books
        if not y:
            for attempt in range(4):
                try:
                    y = googlebooks_year(session, title_kw, author, publisher)
                    break
                except Exception:
                    backoff_sleep(attempt)

        cache[cache_key] = y or ""
        df.at[i, "year_first_published"] = y or ""
        time.sleep(0.12)

        if (i + 1) % 300 == 0:
            save_cache(cache)
            print(f"[{i+1}/{len(df)}] cache={len(cache)}")

    save_cache(cache)

    # Output in a clean, consistent column order
    cols = ["id", "isbn13", "isbn10", "full_title", "match_source", "match_confidence", "year_first_published"]
    for c in cols:
        if c not in df.columns:
            df[c] = ""

    df[cols].to_csv(out_path, index=False)
    print("Wrote:", out_path)


if __name__ == "__main__":
    main()