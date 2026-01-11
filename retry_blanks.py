#!/usr/bin/env python3
import argparse
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

ABBR_RE = re.compile(r"^[A-Za-zÄÖÜäöüß]{1,4}\.$")  # leave abbrev, but ignore as constraint


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
    q_author = "" if ABBR_RE.match(norm(q_author)) else q_author
    q_pub = "" if ABBR_RE.match(norm(q_pub)) else q_pub

    st = token_score(q_title, cand_title)
    sa = token_score(q_author, cand_author) if q_author and cand_author else 0
    sp = token_score(q_pub, cand_pub) if q_pub and cand_pub else 0

    return 0.75 * st + 0.20 * sa + 0.05 * sp


def backoff_sleep(attempt: int) -> None:
    time.sleep(min(8.0, (2 ** attempt) * 0.6) + random.random() * 0.25)


def make_session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": "rxlog-retry-blanks/1.0", "Accept": "application/json"})
    return s


def openlibrary_candidates(session: requests.Session, title_kw: str, author: str, publisher: str,
                           timeout_connect: float, timeout_read: float) -> List[Dict[str, Any]]:
    # If title_kw empty, fall back to generic q (less precise)
    params: Dict[str, Any] = {
        "limit": 10,
        "fields": "title,subtitle,author_name,publisher,isbn",
    }
    if title_kw:
        params["title"] = title_kw
    else:
        q = " ".join(x for x in [author, publisher] if x)
        if q:
            params["q"] = q
        else:
            return []

    if author:
        params["author"] = author
    if publisher:
        params["publisher"] = publisher

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
        out.append({"full_title": full_title, "author": a, "publisher": p, "isbns": isbns, "source": "openlibrary"})
    return out


def google_candidates(session: requests.Session, title_kw: str, author: str, publisher: str,
                      timeout_connect: float, timeout_read: float) -> List[Dict[str, Any]]:
    q_parts = []
    if title_kw:
        q_parts.append(f"intitle:{title_kw}")
    if author:
        q_parts.append(f"inauthor:{author}")
    if publisher:
        q_parts.append(f"inpublisher:{publisher}")
    q = " ".join(q_parts) if q_parts else ""

    if not q:
        return []

    params: Dict[str, Any] = {"q": q, "maxResults": 10, "printType": "books"}
    api_key = os.getenv("GOOGLE_BOOKS_API_KEY", "").strip()
    if api_key:
        params["key"] = api_key

    r = session.get(GOOGLE_BOOKS, params=params, timeout=(timeout_connect, timeout_read))
    r.raise_for_status()
    items = r.json().get("items", []) or []

    out = []
    for it in items:
        vi = it.get("volumeInfo", {}) or {}
        title = vi.get("title") or ""
        subtitle = vi.get("subtitle") or ""
        full_title = f"{title}: {subtitle}".strip(": ") if subtitle else title
        authors = ", ".join(vi.get("authors") or [])
        pub = vi.get("publisher") or ""
        ids = vi.get("industryIdentifiers") or []
        isbn_list = [x.get("identifier") for x in ids if x.get("identifier")]
        out.append({"full_title": full_title, "author": authors, "publisher": pub, "isbns": isbn_list, "source": "googlebooks"})
    return out


def best_match_retry(session: requests.Session, title_kw: str, author: str, publisher: str,
                     timeout_connect: float, timeout_read: float, max_attempts: int, use_google: bool):
    title_kw = norm(title_kw)
    author = norm(author)
    publisher = norm(publisher)

    author_q = "" if ABBR_RE.match(author) else author
    publisher_q = "" if ABBR_RE.match(publisher) else publisher

    # If title keyword is extremely short and no other strong constraints, skip (reduces garbage matches)
    if len(title_kw) < 3 and not author_q and not publisher_q:
        return "", "", "", "", 0.0

    variants = [
        (title_kw, author_q, publisher_q),
        (title_kw, author_q, ""),
        (title_kw, "", publisher_q),
        (title_kw, "", ""),
    ]

    candidates: List[Dict[str, Any]] = []

    # Open Library with progressively looser filters
    for (t, a, p) in variants:
        for attempt in range(max_attempts):
            try:
                candidates = openlibrary_candidates(session, t, a, p, timeout_connect, timeout_read)
                break
            except Exception:
                backoff_sleep(attempt)
        if candidates:
            break

    # Google fallback (optional)
    if use_google and not candidates:
        for (t, a, p) in variants:
            for attempt in range(max_attempts):
                try:
                    candidates = google_candidates(session, t, a, p, timeout_connect, timeout_read)
                    break
                except Exception:
                    backoff_sleep(attempt)
            if candidates:
                break

    if not candidates:
        return "", "", "", "", 0.0

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
    return best.get("full_title", "") or "", isbn13, isbn10, best.get("source", "") or "", round(conf, 3)


def load_cache(path: str) -> Dict[str, Any]:
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_cache(path: str, cache: Dict[str, Any]) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f)
    os.replace(tmp, path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", default="books_seed_expanded.csv")
    ap.add_argument("--enriched", default="books_enriched.csv")
    ap.add_argument("--out", default="books_enriched_merged.csv")
    ap.add_argument("--cache", default="retry_cache.json")
    ap.add_argument("--timeout-connect", type=float, default=5.0)
    ap.add_argument("--timeout-read", type=float, default=25.0)
    ap.add_argument("--max-attempts", type=int, default=2)
    ap.add_argument("--use-google", action="store_true")
    ap.add_argument("--sleep", type=float, default=0.0)
    ap.add_argument("--log-every", type=int, default=200)
    args = ap.parse_args()

    seed = pd.read_csv(args.seed, dtype=str).fillna("")
    enr = pd.read_csv(args.enriched, dtype=str).fillna("")

    df = seed.merge(enr, on="id", how="left")

    blank_mask = (
        (df["isbn13"].str.strip() == "") &
        (df["isbn10"].str.strip() == "") &
        (df["full_title"].str.strip() == "")
    )
    blanks = df[blank_mask].copy()
    print("Blank rows to retry:", len(blanks))

    session = make_session()
    cache = load_cache(args.cache)

    updates = []
    for n, (_, row) in enumerate(blanks.iterrows(), start=1):
        rid = row["id"]
        title_kw = row.get("titlekeyword", "")
        author = row.get("author", "")
        publisher = row.get("publisher", "")

        key = f"google={args.use_google}|{nlow(author)}||{nlow(publisher)}||{nlow(title_kw)}"
        if key in cache:
            ft, i13, i10, src, conf = cache[key]
        else:
            ft, i13, i10, src, conf = best_match_retry(
                session, title_kw, author, publisher,
                args.timeout_connect, args.timeout_read, args.max_attempts,
                args.use_google
            )
            cache[key] = [ft, i13, i10, src, conf]
            if args.sleep > 0:
                time.sleep(args.sleep)

        if ft or i13 or i10:
            updates.append({
                "id": rid,
                "isbn13_new": i13,
                "isbn10_new": i10,
                "full_title_new": ft,
                "match_source_new": src,
                "match_confidence_new": conf,
            })

        if n % args.log_every == 0:
            save_cache(args.cache, cache)
            print(f"retried {n}/{len(blanks)} | newly filled so far: {len(updates)}")

    save_cache(args.cache, cache)

    upd = pd.DataFrame(updates)
    merged = enr.merge(upd, on="id", how="left")

    # Fill only if original is empty
    def fill_if_empty(orig_col: str, new_col: str):
        o = merged[orig_col].astype(str).fillna("")
        n = merged[new_col].astype(str).fillna("")
        merged[orig_col] = o.where(o.str.strip() != "", n)

    for oc, nc in [
        ("isbn13", "isbn13_new"),
        ("isbn10", "isbn10_new"),
        ("full_title", "full_title_new"),
        ("match_source", "match_source_new"),
        ("match_confidence", "match_confidence_new"),
    ]:
        if nc not in merged.columns:
            merged[nc] = ""
        fill_if_empty(oc, nc)

    merged = merged[["id", "isbn13", "isbn10", "full_title", "match_source", "match_confidence"]]
    merged.to_csv(args.out, index=False)
    print("Wrote:", args.out)
    print("Newly filled rows:", len(upd))


if __name__ == "__main__":
    main()