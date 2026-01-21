import React, { useEffect, useMemo, useState } from "react";
import { getSuggestions, ignoreIssue, listIssues, resolveIssue, retryIssue } from "./api";

function fmtDate(iso) {
  if (!iso) return "-";
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

export default function SyncIssuesPanel() {
  const [loading, setLoading] = useState(false);
  const [issues, setIssues] = useState([]);
  const [err, setErr] = useState("");

  const [openId, setOpenId] = useState(null);
  const [suggestions, setSuggestions] = useState({}); // issueId -> suggestions json
  const [suggestLoading, setSuggestLoading] = useState({}); // issueId -> boolean

  const [selected, setSelected] = useState({}); // issueId -> bookCandidate

  async function reload() {
    setLoading(true);
    setErr("");
    try {
      const data = await listIssues("open", 50);
      setIssues(Array.isArray(data) ? data : data?.items ?? []);
    } catch (e) {
      setErr(e?.message || String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
  }, []);

  async function toggle(issueId) {
    const next = openId === issueId ? null : issueId;
    setOpenId(next);

    if (next && !suggestions[next] && !suggestLoading[next]) {
      setSuggestLoading((m) => ({ ...m, [next]: true }));
      try {
        const s = await getSuggestions(next, 200);
        setSuggestions((m) => ({ ...m, [next]: s }));
      } catch (e) {
        setSuggestions((m) => ({ ...m, [next]: { _error: e?.message || String(e) } }));
      } finally {
        setSuggestLoading((m) => ({ ...m, [next]: false }));
      }
    }
  }

  async function onResolve(issueId) {
    const book = selected[issueId];
    if (!book?.bookId) return;

    await resolveIssue(issueId, book.bookId);

    setOpenId(null);
    setSelected((m) => {
      const copy = { ...m };
      delete copy[issueId];
      return copy;
    });
    await reload();
  }

  async function onIgnore(issueId) {
    await ignoreIssue(issueId);
    setOpenId(null);
    await reload();
  }

  async function onRetry(issueId) {
    await retryIssue(issueId);
    await reload();
  }

  const rows = useMemo(() => {
    const arr = Array.isArray(issues) ? [...issues] : [];
    arr.sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
    return arr;
  }, [issues]);

  return (
    <div style={{ display: "grid", gap: 10 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline" }}>
        <div style={{ fontWeight: 700 }}>Sync Issues</div>
        <button type="button" onClick={reload} disabled={loading}>
          {loading ? "…" : "Reload"}
        </button>
      </div>

      {err && <div style={{ color: "#b00020" }}>Fehler: {err}</div>}
      {!loading && rows.length === 0 && <div style={{ color: "#555" }}>Keine offenen Issues 🎉</div>}

      {rows.map((issue) => {
        const id = issue.id; // MobileIssueDto is camelCase and has id
        const isOpen = openId === id;
        const s = suggestions[id];
        const sel = selected[id];

        return (
          <div key={id} style={{ border: "1px solid #e5e7eb", borderRadius: 10, background: "#fff" }}>
            <div
              style={{
                padding: "10px 12px",
                display: "grid",
                gridTemplateColumns: "160px 170px 160px 90px 1fr 120px",
                gap: 10,
                alignItems: "center",
              }}
            >
              <div style={{ color: "#555", fontSize: 12 }}>{fmtDate(issue.createdAt)}</div>

              <div style={{ fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace", fontSize: 12 }}>
                {issue.errorCode || "-"}
              </div>

              <div style={{ fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" }}>
                {issue.barcode || "-"}
              </div>

              <div>{issue.pages ?? "-"}</div>

              <div style={{ color: "#333" }}>{issue.message || ""}</div>

              <div style={{ display: "flex", justifyContent: "flex-end" }}>
                <button type="button" onClick={() => toggle(id)}>
                  {isOpen ? "▲" : "▼"}
                </button>
              </div>
            </div>

            {isOpen && (
              <div style={{ borderTop: "1px solid #eee", padding: 12, display: "grid", gap: 12 }}>
                <div style={{ color: "#666", fontSize: 12 }}>
                  status: {issue.status || "-"}
                  {" · "}
                  readingStatus: {issue.readingStatus || "-"}
                  {issue.readingStatusChangedAt ? ` (${fmtDate(issue.readingStatusChangedAt)})` : ""}
                  {" · "}
                  topBook: {String(issue.topBook ?? false)}
                  {issue.topBookSetAt ? ` (${fmtDate(issue.topBookSetAt)})` : ""}
                </div>

                <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
                  <button type="button" onClick={() => onIgnore(id)} style={{ borderColor: "#b00020" }}>
                    Ignore
                  </button>

                  {issue.errorCode === "SYNC_TECHNICAL_ERROR" && (
                    <button type="button" onClick={() => onRetry(id)}>
                      Retry
                    </button>
                  )}

                  <button type="button" onClick={() => onResolve(id)} disabled={!sel?.bookId}>
                    Resolve (link)
                  </button>
                </div>

                {suggestLoading[id] && <div style={{ color: "#555" }}>Loading suggestions…</div>}

                {s?._error && <div style={{ color: "#b00020" }}>Suggestions Fehler: {s._error}</div>}

                {/* A) Pages vorhanden -> gleiche Seitenzahl */}
                {Array.isArray(s?.samePagesBooks) && s.samePagesBooks.length > 0 && (
                  <div style={{ display: "grid", gap: 6 }}>
                    <div style={{ fontWeight: 700 }}>
                      Gleiche Seitenzahl ({s.samePagesCount ?? s.samePagesBooks.length})
                    </div>
                    {s.samePagesBooks.map((b) => (
                      <CandidateRow
                        key={b.bookId}
                        book={b}
                        onSelect={() => setSelected((m) => ({ ...m, [id]: b }))}
                        selected={sel?.bookId === b.bookId}
                      />
                    ))}
                  </div>
                )}

                {/* B) Keine Pages -> confusable (aus config) */}
                {Array.isArray(s?.confusableBarcodeSuggestions) && s.confusableBarcodeSuggestions.length > 0 && (
                  <div style={{ display: "grid", gap: 10 }}>
                    <div style={{ fontWeight: 700 }}>Ähnliche Barcodes (Config)</div>

                    {s.confusableBarcodeSuggestions.map((g) => (
                      <div key={g.barcode} style={{ border: "1px solid #f0f0f0", borderRadius: 10, padding: 10 }}>
                        <div style={{ fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" }}>
                          {g.barcode}
                        </div>
                        <div style={{ height: 6 }} />
                        {(g.books || []).length === 0 ? (
                          <div style={{ color: "#666", fontSize: 12 }}>Keine Bücher zu diesem Barcode</div>
                        ) : (
                          (g.books || []).map((b) => (
                            <CandidateRow
                              key={b.bookId}
                              book={b}
                              onSelect={() => setSelected((m) => ({ ...m, [id]: b }))}
                              selected={sel?.bookId === b.bookId}
                            />
                          ))
                        )}
                      </div>
                    ))}
                  </div>
                )}

                {!suggestLoading[id] &&
                  !s?._error &&
                  !(Array.isArray(s?.samePagesBooks) && s.samePagesBooks.length) &&
                  !(Array.isArray(s?.confusableBarcodeSuggestions) && s.confusableBarcodeSuggestions.length) && (
                    <div style={{ color: "#666" }}>Keine Vorschläge verfügbar.</div>
                  )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function CandidateRow({ book, onSelect, selected }) {
  const barcodesText =
    Array.isArray(book.barcodes) && book.barcodes.length > 0
      ? book.barcodes.join(", ")
      : "-";

  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "1fr 140px",
        gap: 10,
        alignItems: "center",
        padding: "8px 10px",
        borderRadius: 8,
        background: selected ? "#eef6ff" : "#fafbfc",
        border: "1px solid #e5e7eb",
      }}
    >
      <div>
        <div style={{ fontWeight: 600 }}>
          {(book.author || "—") + (book.titleKeyword ? ` – ${book.titleKeyword}` : "")}
        </div>
        <div style={{ color: "#666", fontSize: 12 }}>
          Pages: {book.pages ?? "-"} · Barcodes:{" "}
          <span style={{ fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace" }}>
            {barcodesText}
          </span>
        </div>
      </div>
      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <button type="button" onClick={onSelect}>
          Select
        </button>
      </div>
    </div>
  );
}