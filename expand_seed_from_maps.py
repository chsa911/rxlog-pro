#!/usr/bin/env python3
import argparse
import csv
import os
import re
from typing import Dict

import pandas as pd
# Your rule: only a single token with dot, 1–4 letters.
# Examples that qualify: "v.", "dt.", "stv."
# Examples that do NOT qualify: "meier, v.", "stv.,dt.", "unknown", "v"
RE_ABBR_FIELD = re.compile(r"^[A-Za-zÄÖÜäöüß]{1,4}\.$")


def load_map(path: str) -> Dict[str, str]:
    """
    Robust CSV parsing for mapping files (authors.csv / publishers.csv).

    Expected headers include at least:
      - abbr_norm
      - full

    If a row contains extra commas (unquoted), we merge any extra columns into 'full'.
    """
    if not os.path.exists(path):
        raise FileNotFoundError(f"Mapping file not found: {path}")

    mapping: Dict[str, str] = {}

    with open(path, "r", encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        header = next(reader, None)
        if not header:
            raise ValueError(f"{path} is empty")

        header = [h.strip() for h in header]
        try:
            i_abbr = header.index("abbr_norm")
            i_full = header.index("full")
        except ValueError:
            raise ValueError(
                f"{path} must have columns named 'abbr_norm' and 'full'. Got headers: {header}"
            )

        expected_len = len(header)

        for lineno, row in enumerate(reader, start=2):
            if not row or all(not (c or "").strip() for c in row):
                continue

            # Too many columns -> merge the extras into the last column (full)
            if len(row) > expected_len:
                row = row[: expected_len - 1] + [",".join(row[expected_len - 1 :])]

            # Too few columns -> pad
            if len(row) < expected_len:
                row = row + [""] * (expected_len - len(row))

            abbr = (row[i_abbr] or "").strip().lower()
            full = (row[i_full] or "").strip()

            if abbr and full:
                mapping[abbr] = full

    return mapping


def expand_field(value: str, mapping: Dict[str, str]) -> str:
    """
    Expand ONLY if the entire field is exactly one abbreviation token
    matching RE_ABBR_FIELD (1–4 letters + dot).

    Never expands inside longer strings like "meier, v.".
    """
    if not isinstance(value, str):
        return value

    raw = value.strip()
    if not raw:
        return raw

    if not RE_ABBR_FIELD.match(raw):
        return raw  # not a standalone abbrev token -> leave unchanged

    key = raw.lower()

    # Expand only if mapping contains it; allow dot/no-dot variations
    # (some maps might store "stv" while your data has "stv.")
    expanded = mapping.get(key)
    if expanded is None:
        expanded = mapping.get(key[:-1])  # without the trailing dot

    return expanded if expanded is not None else raw


def main():
    ap = argparse.ArgumentParser(
        description="Expand author/publisher abbreviations in books_seed.csv using authors.csv / publishers.csv."
    )
    ap.add_argument("--seed", default="books_seed.csv", help="Input seed CSV (id,author,publisher,titlekeyword)")
    ap.add_argument("--authors", default="authors.csv", help="Author mapping CSV with columns abbr_norm,full")
    ap.add_argument("--publishers", default="publishers.csv", help="Publisher mapping CSV with columns abbr_norm,full")
    ap.add_argument("--out", default="books_seed_expanded.csv", help="Output CSV")
    args = ap.parse_args()

    seed = pd.read_csv(args.seed, dtype=str).fillna("")
    for c in ["id", "author", "publisher", "titlekeyword"]:
        if c not in seed.columns:
            raise ValueError(f"Missing required column in seed: {c}")

    author_map = load_map(args.authors)
    publisher_map = load_map(args.publishers)

    # Keep originals for auditing/debugging
    seed["author_raw"] = seed["author"]
    seed["publisher_raw"] = seed["publisher"]

    seed["author"] = seed["author"].apply(lambda x: expand_field(x, author_map))
    seed["publisher"] = seed["publisher"].apply(lambda x: expand_field(x, publisher_map))

    seed.to_csv(args.out, index=False)

    changed_auth = (seed["author"] != seed["author_raw"]).sum()
    changed_pub = (seed["publisher"] != seed["publisher_raw"]).sum()

    print("Wrote:", args.out)
    print("Changed author rows:", int(changed_auth))
    print("Changed publisher rows:", int(changed_pub))


if __name__ == "__main__":
    main()