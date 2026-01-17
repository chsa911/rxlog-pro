// frontend-react/src/App.jsx
import React, { useEffect, useRef, useState } from 'react';
import { parseDimensionToMM } from './lib/dimensions';
import SyncIssuesPanel from './app/syncIssues/SyncIssuesPanel';

// ---------------------------
// ISBN helpers
// ---------------------------
function normalizeIsbn(input) {
  return (input || '').replace(/[^0-9Xx]/g, '').toUpperCase();
}

function pickKeywordsFromTitle(title) {
  const stop = new Set([
    // DE
    'der',
    'die',
    'das',
    'den',
    'dem',
    'des',
    'ein',
    'eine',
    'einer',
    'eines',
    'einem',
    'einen',
    'und',
    'oder',
    'im',
    'in',
    'am',
    'an',
    'auf',
    'aus',
    'bei',
    'mit',
    'von',
    'für',
    'zum',
    'zur',
    'über',
    'unter',
    'durch',
    'gegen',
    'ohne',
    'um',
    // EN
    'the',
    'a',
    'an',
    'and',
    'or',
    'in',
    'on',
    'at',
    'of',
    'to',
    'for',
    'with',
    'from',
    'by',
    'into',
    'over',
    'under',
    'without',
  ]);

  const tokens = (title || '')
    .split(/[\s:;,.!?(){}\[\]"'“”’\-–—/\\]+/)
    .map((w) => w.trim())
    .filter(Boolean);

  const chosen = [];
  for (let i = 0; i < tokens.length; i++) {
    const raw = tokens[i];
    const cleaned = raw.replace(/[^\p{L}\p{N}]+/gu, '');
    if (!cleaned) continue;

    const low = cleaned.toLowerCase();
    if (cleaned.length < 3) continue;
    if (stop.has(low)) continue;
    if (chosen.some((c) => c.kw.toLowerCase() === low)) continue;

    chosen.push({ kw: cleaned, pos: i + 1 });
    if (chosen.length >= 3) break;
  }

  return {
    kw1: chosen[0]?.kw || '',
    pos1: chosen[0]?.pos ? String(chosen[0].pos) : '',
    kw2: chosen[1]?.kw || '',
    pos2: chosen[1]?.pos ? String(chosen[1].pos) : '',
    kw3: chosen[2]?.kw || '',
    pos3: chosen[2]?.pos ? String(chosen[2].pos) : '',
  };
}

// ---------------------------
// ISBN -> Metadata lookup (already existing)
// ---------------------------
async function fetchIsbnMetadata(cleanIsbn) {
  const amazonUrl = `https://www.amazon.de/s?k=${encodeURIComponent(cleanIsbn)}`;

  // 1) Preferred: backend proxy
  try {
    const res = await fetch(`/api/register/isbn/${cleanIsbn}`);
    if (res.ok) {
      const j = await res.json();
      return {
        isbn: cleanIsbn,
        title: j.title || '',
        authors: Array.isArray(j.authors) ? j.authors : [],
        publisher: j.publisher || '',
        pages: Number.isFinite(j.pages) ? j.pages : null,
        coverUrl: j.coverUrl || '',
        infoUrl: j.infoUrl || '',
        buyUrl: j.buyUrl || '',
        amazonUrl: j.amazonUrl || amazonUrl,
      };
    }
  } catch {
    // ignore
  }

  // 2) Google Books
  try {
    const res = await fetch(`https://www.googleapis.com/books/v1/volumes?q=isbn:${cleanIsbn}`);
    if (res.ok) {
      const j = await res.json();
      const item = j?.items?.[0];
      const v = item?.volumeInfo;
      const sale = item?.saleInfo;
      if (v) {
        return {
          isbn: cleanIsbn,
          title: v.title || '',
          authors: Array.isArray(v.authors) ? v.authors : [],
          publisher: v.publisher || '',
          pages: Number.isFinite(v.pageCount) ? v.pageCount : null,
          coverUrl: v?.imageLinks?.thumbnail || v?.imageLinks?.smallThumbnail || '',
          infoUrl: v.infoLink || v.previewLink || '',
          buyUrl: sale?.buyLink || '',
          amazonUrl,
        };
      }
    }
  } catch {
    // ignore
  }

  // 3) Open Library
  try {
    const res = await fetch(
      `https://openlibrary.org/api/books?bibkeys=ISBN:${cleanIsbn}&format=json&jscmd=data`,
    );
    if (res.ok) {
      const j = await res.json();
      const b = j?.[`ISBN:${cleanIsbn}`];
      if (b) {
        const olInfo = b?.url
          ? `https://openlibrary.org${b.url}`
          : b?.key
            ? `https://openlibrary.org${b.key}`
            : '';

        return {
          isbn: cleanIsbn,
          title: b.title || '',
          authors: Array.isArray(b.authors) ? b.authors.map((a) => a?.name).filter(Boolean) : [],
          publisher: b?.publishers?.[0]?.name || '',
          pages: Number.isFinite(b.number_of_pages) ? b.number_of_pages : null,
          coverUrl: b?.cover?.medium || b?.cover?.large || b?.cover?.small || '',
          infoUrl: olInfo,
          buyUrl: '',
          amazonUrl,
        };
      }
    }
  } catch {
    // ignore
  }

  return null;
}

export default function App() {
  // core form state
  const [author, setAuthor] = useState('');
  const [publisher, setPublisher] = useState('');
  const [pages, setPages] = useState('');

  // full title field (manual, can be filled by ISBN but not overwritten)
  const [fullTitle, setFullTitle] = useState('');

  // ISBN lookup
  const [isbn, setIsbn] = useState('');
  const [isbnStatus, setIsbnStatus] = useState(''); // '', 'loading', 'ok', 'notfound', 'error'
  const [isbnTitlePreview, setIsbnTitlePreview] = useState('');
  const [isbnAuthorsPreview, setIsbnAuthorsPreview] = useState(''); // tooltip text
  const [isbnCoverUrl, setIsbnCoverUrl] = useState('');
  const [infoUrl, setInfoUrl] = useState('');
  const [buyUrl, setBuyUrl] = useState('');
  const [amazonUrl, setAmazonUrl] = useState('');
  const lastIsbnLookupRef = useRef('');

  // Reverse ISBN lookup (author/title/publisher -> ISBN candidates)
  const [isbnFindStatus, setIsbnFindStatus] = useState(''); // '', 'loading', 'error'
  const [isbnCandidates, setIsbnCandidates] = useState([]); // [{ isbn, title, authors, publisher }]

  // match backend naming
  const [titleKeyword, setTitleKeyword] = useState('');
  const [titleKeywordPosition, setTitleKeywordPosition] = useState('');
  const [titleKeyword2, setTitleKeyword2] = useState('');
  const [titleKeyword2Position, setTitleKeyword2Position] = useState('');
  const [titleKeyword3, setTitleKeyword3] = useState('');
  const [titleKeyword3Position, setTitleKeyword3Position] = useState('');

  // dimensions (raw + normalized)
  const [widthRaw, setWidthRaw] = useState('');
  const [heightRaw, setHeightRaw] = useState('');
  const [widthMM, setWidthMM] = useState(null);
  const [heightMM, setHeightMM] = useState(null);

  // derived cm
  const widthCm = widthMM != null ? widthMM / 10 : null;
  const heightCm = heightMM != null ? heightMM / 10 : null;

  // barcode & status
  const [barcode, setBarcode] = useState('');
  const [color, setColor] = useState('');
  const [position, setPosition] = useState('');
  const [readingStatus, setReadingStatus] = useState('in_progress');
  const [topBook, setTopBook] = useState(false);

  // optional classification (can be NULL on backend)
  // ''    = not specified
  // true  = Fiction
  // false = Non-Fiction
  const [isFiction, setIsFiction] = useState(''); // '', 'true', 'false'
  const [genre, setGenre] = useState('');
  const [subGenre, setSubGenre] = useState('');
  const [themes, setThemes] = useState('');

  const [log, setLog] = useState([]);

  function handleWidthBlur() {
    setWidthMM(parseDimensionToMM(widthRaw));
  }
  function handleHeightBlur() {
    setHeightMM(parseDimensionToMM(heightRaw));
  }

  // ---------------------------
  // Find ISBN candidates from author/title/publisher (Google Books)
  // ---------------------------
  async function findIsbnCandidates() {
    const titleQ = [titleKeyword, titleKeyword2, titleKeyword3].filter(Boolean).join(' ').trim();
    const a = (author || '').trim();
    const p = (publisher || '').trim();

    if (!titleQ && !a && !p) {
      setLog((l) => ['ISBN-Suche: Bitte Autor/Verlag/Titel-Stichwort(e) eingeben.', ...l]);
      return;
    }

    setIsbnFindStatus('loading');
    setIsbnCandidates([]);

    try {
      const parts = [];
      if (a) parts.push(`inauthor:${a}`);
      if (p) parts.push(`inpublisher:${p}`);
      if (titleQ) parts.push(`intitle:${titleQ}`);

      const q = encodeURIComponent(parts.join(' '));
      const url = `https://www.googleapis.com/books/v1/volumes?q=${q}&maxResults=8`;

      const res = await fetch(url);
      if (!res.ok) throw new Error(`Google Books ${res.status}`);

      const j = await res.json();
      const items = Array.isArray(j?.items) ? j.items : [];

      const seen = new Set();
      const cands = items
        .map((it) => {
          const v = it?.volumeInfo;
          if (!v) return null;

          const ids = Array.isArray(v.industryIdentifiers) ? v.industryIdentifiers : [];
          const isbn13 = ids.find((x) => x.type === 'ISBN_13')?.identifier;
          const isbn10 = ids.find((x) => x.type === 'ISBN_10')?.identifier;
          const raw = isbn13 || isbn10;
          if (!raw) return null;

          const clean = normalizeIsbn(raw);
          if (!clean || seen.has(clean)) return null;
          seen.add(clean);

          return {
            isbn: clean,
            title: v.title || '',
            authors: Array.isArray(v.authors) ? v.authors.join(', ') : '',
            publisher: v.publisher || '',
          };
        })
        .filter(Boolean);

      setIsbnCandidates(cands);
      setIsbnFindStatus('');

      if (cands.length === 0) {
        setLog((l) => ['ISBN-Suche (Google): Keine Treffer.', ...l]);
      }
    } catch (e) {
      setIsbnFindStatus('error');
      setLog((l) => [`ISBN-Suche (Google) Fehler: ${e?.message || String(e)}`, ...l]);
    }
  }

  // ---------------------------
  // ISBN effect (debounced)  ISBN -> metadata
  // ---------------------------
  useEffect(() => {
    let cancelled = false;

    const clean = normalizeIsbn(isbn);

    if (!clean) {
      setIsbnStatus('');
      setIsbnTitlePreview('');
      setIsbnAuthorsPreview('');
      setIsbnCoverUrl('');
      setInfoUrl('');
      setBuyUrl('');
      setAmazonUrl('');
      lastIsbnLookupRef.current = '';
      return;
    }

    if (!(clean.length === 10 || clean.length === 13)) {
      setIsbnStatus('');
      setIsbnTitlePreview('');
      setIsbnAuthorsPreview('');
      setIsbnCoverUrl('');
      setInfoUrl('');
      setBuyUrl('');
      setAmazonUrl('');
      return;
    }

    if (clean === lastIsbnLookupRef.current) return;

    const t = setTimeout(async () => {
      try {
        setIsbnStatus('loading');

        const meta = await fetchIsbnMetadata(clean);
        if (cancelled) return;

        lastIsbnLookupRef.current = clean;

        if (!meta) {
          setIsbnStatus('notfound');
          setIsbnTitlePreview('');
          setIsbnAuthorsPreview('');
          setIsbnCoverUrl('');
          setInfoUrl('');
          setBuyUrl('');
          setAmazonUrl(`https://www.amazon.de/s?k=${encodeURIComponent(clean)}`);
          return;
        }

        setIsbnStatus('ok');
        setIsbnTitlePreview(meta.title || '');
        setIsbnAuthorsPreview(meta.authors?.length ? meta.authors.join(', ') : '');
        setIsbnCoverUrl(meta.coverUrl || '');
        setInfoUrl(meta.infoUrl || '');
        setBuyUrl(meta.buyUrl || '');
        setAmazonUrl(meta.amazonUrl || `https://www.amazon.de/s?k=${encodeURIComponent(clean)}`);

        // Fill only if empty (do not overwrite user input)
        if (!fullTitle && meta.title) setFullTitle(meta.title);
        if (!author && meta.authors?.length) setAuthor(meta.authors.join(', '));
        if (!publisher && meta.publisher) setPublisher(meta.publisher);
        if (!pages && meta.pages) setPages(String(meta.pages));

        if (meta.title) {
          const picked = pickKeywordsFromTitle(meta.title);

          if (!titleKeyword && picked.kw1) setTitleKeyword(picked.kw1);
          if (!titleKeywordPosition && picked.pos1) setTitleKeywordPosition(picked.pos1);

          if (!titleKeyword2 && picked.kw2) setTitleKeyword2(picked.kw2);
          if (!titleKeyword2Position && picked.pos2) setTitleKeyword2Position(picked.pos2);

          if (!titleKeyword3 && picked.kw3) setTitleKeyword3(picked.kw3);
          if (!titleKeyword3Position && picked.pos3) setTitleKeyword3Position(picked.pos3);
        }
      } catch {
        if (cancelled) return;
        lastIsbnLookupRef.current = clean;
        setIsbnStatus('error');
        setIsbnTitlePreview('');
        setIsbnAuthorsPreview('');
        setIsbnCoverUrl('');
        setInfoUrl('');
        setBuyUrl('');
        setAmazonUrl(`https://www.amazon.de/s?k=${encodeURIComponent(clean)}`);
      }
    }, 500);

    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [
    isbn,
    author,
    publisher,
    pages,
    fullTitle,
    titleKeyword,
    titleKeywordPosition,
    titleKeyword2,
    titleKeyword2Position,
    titleKeyword3,
    titleKeyword3Position,
  ]);

  // release helper
  async function releaseCurrentBarcode(reason = '') {
    if (!barcode) return;
    try {
      await fetch('/api/barcodes/release', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code: barcode }),
      });
      setLog((l) => [`Barcode freigegeben${reason ? `: ${reason}` : ''}: ${barcode}`, ...l]);
    } catch (_) {
      /* ignore */
    }
    setBarcode('');
    setColor('');
    setPosition('');
  }

  // auto-assign on valid dimensions
  useEffect(() => {
    let cancelled = false;

    async function maybeAssign() {
      const ready =
        Number.isFinite(widthMM) && Number.isFinite(heightMM) && widthMM > 0 && heightMM > 0;
      if (!ready) {
        if (barcode) await releaseCurrentBarcode('Dimensionen gelöscht/ungültig');
        return;
      }
      if (barcode) await releaseCurrentBarcode('Dimensionen geändert');

      const payload = {
        width: widthMM,
        height: heightMM,
        widthCm: widthCm,
        heightCm: heightCm,
      };

      try {
        const res = await fetch('/api/barcodes/assignForDimensions', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(payload),
        });

        if (cancelled) return;

        if (!res.ok) {
          let msg = `Kein verfügbarer Barcode (${res.status}).`;
          try {
            const err = await res.json();
            if (err?.type === 'NO_RULE_APPLIES')
              msg = 'Kein Größen-Regel passt zu den eingegebenen Maßen.';
            if (err?.type === 'NO_STOCK')
              msg = 'Kein Barcode verfügbar für die ermittelte Kombination.';
            if (err?.type === 'DB_UNAVAILABLE')
              msg = 'Barcode-Service/DB derzeit nicht erreichbar.';
          } catch {}
          setBarcode('');
          setColor('');
          setPosition('');
          setLog((l) => [msg, ...l]);
          return;
        }

        const data = await res.json().catch(() => ({}));
        if (cancelled) return;

        const code = data.barcode ?? data.code ?? '';
        const clr = data.rule ?? data.color ?? '';
        const pos = data.position ?? '';

        if (!code) {
          setLog((l) => ['Antwort ohne barcode/code Feld erhalten.', ...l]);
          return;
        }

        setBarcode(code);
        setColor(clr);
        setPosition(pos);
        setLog((l) => [`Barcode zugewiesen: ${code} (${clr || '-'} · ${pos || '-'})`, ...l]);
      } catch (e) {
        if (cancelled) return;
        setLog((l) => [`Netzwerkfehler bei Zuweisung: ${e.message}`, ...l]);
      }
    }

    void maybeAssign();
    return () => {
      cancelled = true;
    };
  }, [widthMM, heightMM]); // eslint-disable-line react-hooks/exhaustive-deps

  async function onSubmit(e) {
    e.preventDefault();

    if (!barcode) {
      alert('Bitte zuerst Barcode ermitteln.');
      return;
    }
    if (!Number.isFinite(widthMM) || !Number.isFinite(heightMM)) {
      alert('Bitte Buchbreite/-höhe eingeben.');
      return;
    }

    const cleanIsbn = normalizeIsbn(isbn);
    const effectivePurchaseUrl = buyUrl || infoUrl || amazonUrl || '';

    const basePayload = {
      author,
      publisher,
      pages: pages ? Number(pages) : null,

      titleKeyword: titleKeyword || null,
      titleKeywordPosition: titleKeywordPosition ? Number(titleKeywordPosition) : null,
      titleKeyword2: titleKeyword2 || null,
      titleKeyword2Position: titleKeyword2Position ? Number(titleKeyword2Position) : null,
      titleKeyword3: titleKeyword3 || null,
      titleKeyword3Position: titleKeyword3Position ? Number(titleKeyword3Position) : null,

      barcode,
      readingStatus,
      topBook,

      // optional classification
      isFiction: isFiction === '' ? null : isFiction === 'true',
      genre: (genre || '').trim() || null,
      subGenre: (subGenre || '').trim() || null,
      themes: (themes || '').trim() || null,

      width: widthMM,
      height: heightMM,
    };

    const payloadWithLinks = {
      ...basePayload,
      // include manual full title if backend supports it
      titleFull: (fullTitle || '').trim() || null,

      isbn13: cleanIsbn || null,
      purchaseSource: buyUrl
        ? 'google_books_buy'
        : infoUrl
          ? 'google_books_info'
          : amazonUrl
            ? 'amazon_search'
            : null,
      purchaseUrl: effectivePurchaseUrl || null,
    };

    async function postRegister(payload) {
      return fetch('/api/register/book', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      });
    }

    try {
      // 1) newest backend: supports ISBN/link fields + optional classification (+ titleFull if you added it)
      let res = await postRegister(payloadWithLinks);

      // 2) older backend: supports classification but not ISBN/link (and likely not titleFull)
      if (!res.ok && res.status === 400) {
        res = await postRegister(basePayload);
        if (res.ok) {
          setLog((l) => ['Hinweis: ISBN/Links nicht gespeichert (Backend noch ohne Felder).', ...l]);
        }
      }

      // 3) very old backend: supports neither ISBN/link nor classification fields
      if (!res.ok && res.status === 400) {
        const legacyPayload = {
          author,
          publisher,
          pages: pages ? Number(pages) : null,

          titleKeyword: titleKeyword || null,
          titleKeywordPosition: titleKeywordPosition ? Number(titleKeywordPosition) : null,
          titleKeyword2: titleKeyword2 || null,
          titleKeyword2Position: titleKeyword2Position ? Number(titleKeyword2Position) : null,
          titleKeyword3: titleKeyword3 || null,
          titleKeyword3Position: titleKeyword3Position ? Number(titleKeyword3Position) : null,

          barcode,
          readingStatus,
          topBook,

          width: widthMM,
          height: heightMM,
        };

        res = await postRegister(legacyPayload);
        if (res.ok) {
          setLog((l) => [
            'Hinweis: ISBN/Links & Klassifikation nicht gespeichert (Backend noch ohne Felder).',
            ...l,
          ]);
        }
      }

      // ✅ IMPORTANT: this was broken before (caused "Expected finally but found const")
      if (!res.ok) {
        await releaseCurrentBarcode('Registrierung fehlgeschlagen');
        throw new Error('Registrierung fehlgeschlagen: ' + res.status);
      }

      const data = await res.json();
      setLog((l) => [`Gespeichert: ${data.bookId} mit ${barcode} [${readingStatus}]`, ...l]);
      resetForm();
    } catch (err) {
      setLog((l) => [`Fehler: ${err.message}`, ...l]);
      alert(err.message);
    }
  }

  function resetForm() {
    setAuthor('');
    setPublisher('');
    setPages('');
    setFullTitle('');

    setIsbn('');
    setIsbnStatus('');
    setIsbnTitlePreview('');
    setIsbnAuthorsPreview('');
    setIsbnCoverUrl('');
    setInfoUrl('');
    setBuyUrl('');
    setAmazonUrl('');
    lastIsbnLookupRef.current = '';

    setIsbnFindStatus('');
    setIsbnCandidates([]);

    setTitleKeyword('');
    setTitleKeywordPosition('');
    setTitleKeyword2('');
    setTitleKeyword2Position('');
    setTitleKeyword3('');
    setTitleKeyword3Position('');

    setWidthRaw('');
    setHeightRaw('');
    setWidthMM(null);
    setHeightMM(null);

    setBarcode('');
    setColor('');
    setPosition('');
    setReadingStatus('in_progress');
    setTopBook(false);

    setIsFiction('');
    setGenre('');
    setSubGenre('');
    setThemes('');
  }

  // Tooltip text
  const isbnTooltip = isbnTitlePreview
    ? `ISBN → ${isbnTitlePreview}${isbnAuthorsPreview ? ` — ${isbnAuthorsPreview}` : ''}`
    : 'Keine ISBN-Infos geladen';

  return (
    <div style={{ fontFamily: 'system-ui, sans-serif', padding: '2rem', maxWidth: 900, margin: '0 auto' }}>
      <h1>RxLog – Buch registrieren</h1>

      <form onSubmit={onSubmit} className="grid" style={{ gap: '0.75rem', maxWidth: 720 }}>
        <label>
          ISBN (optional)

          {/* ISBN input + reverse search button + tooltip */}
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input
              value={isbn}
              onChange={(e) => {
                setIsbn(e.target.value);
                // close candidate list when user starts typing ISBN manually
                setIsbnCandidates([]);
                setIsbnFindStatus('');
              }}
              placeholder="z. B. 9780140328721"
              inputMode="numeric"
              autoComplete="off"
              style={{ flex: 1 }}
            />

            <button
              type="button"
              onClick={findIsbnCandidates}
              disabled={isbnFindStatus === 'loading'}
              title="ISBN anhand Autor/Verlag/Titel suchen"
            >
              {isbnFindStatus === 'loading' ? 'Suche…' : 'ISBN suchen'}
            </button>

            <span
              title={isbnTooltip}
              style={{
                cursor: 'help',
                userSelect: 'none',
                fontSize: 14,
                padding: '2px 6px',
                border: '1px solid #e5e7eb',
                borderRadius: 6,
                background: '#fff',
                lineHeight: '20px',
              }}
            >
              ℹ️
            </span>
          </div>

          <div
            style={{
              fontSize: 12,
              color: '#555',
              marginTop: 4,
              display: 'flex',
              gap: 10,
              alignItems: 'center',
              flexWrap: 'wrap',
            }}
          >
            <span>
              {isbnStatus === 'loading' && 'Suche…'}
              {isbnStatus === 'ok' && 'Übernommen.'}
              {isbnStatus === 'notfound' && 'Nicht gefunden.'}
              {isbnStatus === 'error' && 'Fehler bei der Suche.'}
            </span>

            {(buyUrl || infoUrl || amazonUrl) && (
              <span style={{ display: 'inline-flex', gap: 10 }}>
                {buyUrl ? (
                  <a href={buyUrl} target="_blank" rel="noopener noreferrer">
                    Kaufen
                  </a>
                ) : null}
                {infoUrl ? (
                  <a href={infoUrl} target="_blank" rel="noopener noreferrer">
                    Info
                  </a>
                ) : null}
                {amazonUrl ? (
                  <a href={amazonUrl} target="_blank" rel="noopener noreferrer">
                    Amazon
                  </a>
                ) : null}
              </span>
            )}
          </div>

          {/* Candidate list (reverse lookup) */}
          {isbnCandidates.length > 0 && (
            <div style={{ marginTop: 8, fontSize: 13 }}>
              <div style={{ color: '#555', marginBottom: 6 }}>Gefundene Kandidaten (bitte auswählen):</div>
              <div style={{ display: 'grid', gap: 6 }}>
                {isbnCandidates.map((c) => (
                  <button
                    key={c.isbn}
                    type="button"
                    onClick={() => {
                      setIsbn(c.isbn); // triggers ISBN->metadata effect
                      setIsbnCandidates([]);
                      setIsbnFindStatus('');
                      setLog((l) => [`ISBN übernommen: ${c.isbn} (${c.title || '—'})`, ...l]);
                    }}
                    style={{
                      textAlign: 'left',
                      padding: '8px 10px',
                      border: '1px solid #e5e7eb',
                      borderRadius: 8,
                      background: '#fff',
                      cursor: 'pointer',
                    }}
                    title={`Übernehmen: ${c.isbn}`}
                  >
                    <div style={{ fontWeight: 600 }}>{c.title || '—'}</div>
                    <div style={{ color: '#666' }}>
                      {c.authors ? c.authors : '—'}
                      {c.publisher ? ` · ${c.publisher}` : ''}
                    </div>
                    <div style={{ color: '#444', marginTop: 2 }}>{c.isbn}</div>
                  </button>
                ))}
              </div>
            </div>
          )}

          {isbnFindStatus === 'error' && (
            <div style={{ marginTop: 6, fontSize: 12, color: '#b00020' }}>
              ISBN-Suche fehlgeschlagen (siehe Logs).
            </div>
          )}

          {isbnCoverUrl ? (
            <div style={{ marginTop: 6 }}>
              <img
                src={isbnCoverUrl}
                alt=""
                style={{ width: 60, height: 'auto', borderRadius: 6, border: '1px solid #e5e7eb' }}
              />
            </div>
          ) : null}
        </label>

        <label>
          Titel (voll)
          <input
            value={fullTitle}
            onChange={(e) => setFullTitle(e.target.value)}
            placeholder="Manuell erfassen (wird von ISBN nur gefüllt, wenn leer)"
          />
        </label>

        <label>
          Autor
          <input
            required
            value={author}
            onChange={(e) => setAuthor(e.target.value)}
            placeholder="z. B. T. Fontane"
          />
        </label>

        <label>
          Verlag
          <input
            required
            value={publisher}
            onChange={(e) => setPublisher(e.target.value)}
            placeholder="z. B. Suhrkamp"
          />
        </label>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
          <label>
            Schlagwort 1
            <input required value={titleKeyword} onChange={(e) => setTitleKeyword(e.target.value)} />
          </label>
          <label>
            Position 1
            <input
              required
              type="number"
              min={1}
              value={titleKeywordPosition ?? ''}
              onChange={(e) => setTitleKeywordPosition(e.target.value)}
            />
          </label>

          <label>
            Schlagwort 2
            <input value={titleKeyword2} onChange={(e) => setTitleKeyword2(e.target.value)} />
          </label>
          <label>
            Position 2
            <input
              type="number"
              min={1}
              value={titleKeyword2Position ?? ''}
              onChange={(e) => setTitleKeyword2Position(e.target.value)}
            />
          </label>

          <label>
            Schlagwort 3
            <input value={titleKeyword3} onChange={(e) => setTitleKeyword3(e.target.value)} />
          </label>
          <label>
            Position 3
            <input
              type="number"
              min={1}
              value={titleKeyword3Position ?? ''}
              onChange={(e) => setTitleKeyword3Position(e.target.value)}
            />
          </label>
        </div>

        <label>
          Seitenzahl
          <input required type="number" min={1} value={pages} onChange={(e) => setPages(e.target.value)} />
        </label>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
          <label>
            Buchbreite (mm oder cm)
            <input
              value={widthRaw}
              onChange={(e) => setWidthRaw(e.target.value)}
              onBlur={handleWidthBlur}
              placeholder="z. B. 105 mm / 10,5 cm / 10"
              inputMode="decimal"
            />
          </label>
          <label>
            Buchhöhe (mm oder cm)
            <input
              value={heightRaw}
              onChange={(e) => setHeightRaw(e.target.value)}
              onBlur={handleHeightBlur}
              placeholder="z. B. 190 mm / 19 cm / 19"
              inputMode="decimal"
            />
          </label>
        </div>

        {(widthMM != null || heightMM != null) && (
          <div style={{ color: '#555', fontSize: 13 }}>
            Normalisiert:&nbsp;
            {widthMM != null && (
              <>
                Breite <b>{widthMM} mm</b> ({(widthMM / 10).toFixed(1)} cm)
              </>
            )}
            {heightMM != null && (
              <>
                , Höhe <b>{heightMM} mm</b> ({(heightMM / 10).toFixed(1)} cm)
              </>
            )}
          </div>
        )}

        <fieldset style={{ marginTop: '0.5rem' }}>
          <legend>Lesestatus</legend>
          <label>
            <input
              type="radio"
              name="rs"
              value="in_progress"
              checked={readingStatus === 'in_progress'}
              onChange={(e) => setReadingStatus(e.target.value)}
            />{' '}
            In Bearbeitung
          </label>{' '}
          <label>
            <input
              type="radio"
              name="rs"
              value="finished"
              checked={readingStatus === 'finished'}
              onChange={(e) => setReadingStatus(e.target.value)}
            />{' '}
            Fertig gelesen
          </label>{' '}
          <label>
            <input
              type="radio"
              name="rs"
              value="abandoned"
              checked={readingStatus === 'abandoned'}
              onChange={(e) => setReadingStatus(e.target.value)}
            />{' '}
            Vorzeitig beendet
          </label>
        </fieldset>

        <fieldset style={{ marginTop: '0.5rem' }}>
          <legend>Klassifikation (optional)</legend>

          <label style={{ display: 'block', marginTop: 6 }}>
            Typ{' '}
            <select value={isFiction} onChange={(e) => setIsFiction(e.target.value)}>
              <option value="">Keine Angabe</option>
              <option value="true">Fiction</option>
              <option value="false">Non-Fiction</option>
            </select>
          </label>

          <label style={{ display: 'block', marginTop: 6 }}>
            Genre
            <input value={genre} onChange={(e) => setGenre(e.target.value)} placeholder="z. B. Krimi" autoComplete="off" />
          </label>

          <label style={{ display: 'block', marginTop: 6 }}>
            Untergenre
            <input
              value={subGenre}
              onChange={(e) => setSubGenre(e.target.value)}
              placeholder="z. B. Thriller"
              autoComplete="off"
            />
          </label>

          <label style={{ display: 'block', marginTop: 6 }}>
            Themen
            <input
              value={themes}
              onChange={(e) => setThemes(e.target.value)}
              placeholder="z. B. Bergsteigen, Alpen"
              autoComplete="off"
            />
          </label>
        </fieldset>

        <label>
          <input type="checkbox" checked={topBook} onChange={(e) => setTopBook(e.target.checked)} /> Top-Buch
        </label>

        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button type="submit" disabled={!barcode}>
            Buch registrieren
          </button>
        </div>

        {barcode && (
          <div style={{ background: '#f6f8fa', padding: '0.5rem', borderRadius: 8, marginTop: '0.5rem' }}>
            <b>Barcode:</b> {barcode}{' '}
            {color || position ? (
              <>
                (<span>{color || '-'}</span> · <span>{position || '-'}</span>)
              </>
            ) : null}
          </div>
        )}
      </form>

      <h3 style={{ marginTop: '1rem' }}>Logs</h3>
      <pre style={{ background: '#f6f8fa', padding: '0.75rem', borderRadius: 8, minHeight: 80 }}>
        {log.join('\n')}
      </pre>

      {/* --- Sync Issues section --- */}
      <details style={{ marginTop: '1.5rem' }}>
        <summary style={{ fontSize: 18, cursor: 'pointer', userSelect: 'none' }}>⚠️ Sync Issues</summary>

        <div
          style={{
            marginTop: '0.75rem',
            background: '#fafbfc',
            border: '1px solid #e5e7eb',
            borderRadius: 10,
            padding: '1rem',
          }}
        >
          <SyncIssuesPanel />
        </div>
      </details>
    </div>
  );
}