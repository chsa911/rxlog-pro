// frontend-react/src/App.jsx
import React, { useEffect, useState } from 'react';
import { parseDimensionToMM } from './lib/dimensions';
import SyncIssuesPanel from './app/syncIssues/SyncIssuesPanel';

export default function App() {
  // core form state
  const [author, setAuthor] = useState('');
  const [publisher, setPublisher] = useState('');
  const [pages, setPages] = useState('');

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

  const [log, setLog] = useState([]);

  function handleWidthBlur() {
    setWidthMM(parseDimensionToMM(widthRaw));
  }
  function handleHeightBlur() {
    setHeightMM(parseDimensionToMM(heightRaw));
  }

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
  }, [widthMM, heightMM]);

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

    const payload = {
      author,
      publisher,
      pages: pages ? Number(pages) : null,

      // match backend DTO exactly
      titleKeyword: titleKeyword || null,
      titleKeywordPosition: titleKeywordPosition ? Number(titleKeywordPosition) : null,
      titleKeyword2: titleKeyword2 || null,
      titleKeyword2Position: titleKeyword2Position ? Number(titleKeyword2Position) : null,
      titleKeyword3: titleKeyword3 || null,
      titleKeyword3Position: titleKeyword3Position ? Number(titleKeyword3Position) : null,

      barcode,
      readingStatus,
      topBook,

      // mm for register service
      width: widthMM,
      height: heightMM,
    };

    try {
      const res = await fetch('/api/register/book', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        await releaseCurrentBarcode('Registrierung fehlgeschlagen');
        throw new Error('Registrierung fehlgeschlagen: ' + res.status);
      }

      const data = await res.json();
      setLog((l) => [`Gespeichert: ${data.bookId} mit ${barcode} [${readingStatus}]`, ...l]);
      resetForm(); // success
    } catch (err) {
      setLog((l) => [`Fehler: ${err.message}`, ...l]);
      alert(err.message);
    }
  }

  function resetForm() {
    setAuthor('');
    setPublisher('');
    setPages('');
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
  }

  // ---------------------------
  // Analytics – Top Authors
  // ---------------------------
  const [aFinished, setAFinished] = useState(true);
  const [aAbandoned, setAAbandoned] = useState(true);
  const [aLimit, setALimit] = useState(10);

  const [topAuthors, setTopAuthors] = useState([]);
  const [analyticsLoading, setAnalyticsLoading] = useState(false);
  const [analyticsError, setAnalyticsError] = useState('');

  async function loadTopAuthors() {
    setAnalyticsLoading(true);
    setAnalyticsError('');
    try {
      const statuses = [];
      if (aFinished) statuses.push('finished');
      if (aAbandoned) statuses.push('abandoned');
      const statusParam = statuses.length ? statuses.join(',') : 'finished,abandoned';

      const params = new URLSearchParams();
      params.set('statuses', statusParam);
      params.set('limit', String(aLimit));

      const res = await fetch('/api/register/analytics/top-authors?' + params.toString());
      if (!res.ok) throw new Error(`Analytics failed: ${res.status}`);
      const data = await res.json();
      setTopAuthors(Array.isArray(data) ? data : []);
    } catch (e) {
      setAnalyticsError(e?.message || String(e));
    } finally {
      setAnalyticsLoading(false);
    }
  }

  // ---------------------------
  // Search & Update (inline)
  // ---------------------------
  const [adminOpen, setAdminOpen] = useState(false);

  // search filters
  const [sAuthor, setSAuthor] = useState('');
  const [sPublisher, setSPublisher] = useState('');
  const [sTitle, setSTitle] = useState('');
  const [sBarcode, setSBarcode] = useState('');
  const [sReadingStatus, setSReadingStatus] = useState(''); // '', in_progress, finished, abandoned
  const [sTopBook, setSTopBook] = useState(''); // '', 'true', 'false'
  const [sLimit, setSLimit] = useState(20);

  // results
  const [results, setResults] = useState([]); // array of rows
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState('');

  function normalizeRow(b) {
    const id = b.id ?? b.bookId ?? b.book_id ?? b.uuid;
    const width = b.width_mm ?? b.width ?? null;
    const height = b.height_mm ?? b.height ?? null;
    const barcodes = Array.isArray(b.barcodes) ? b.barcodes : b.barcode ? [b.barcode] : [];

    const base = {
      id,
      author: b.author || '',
      publisher: b.publisher || '',
      pages: b.pages ?? '',
      readingStatus: b.readingStatus || 'in_progress',
      topBook: !!b.topBook,
      widthMM: width,
      heightMM: height,
      widthRawRow: width != null ? String(width) : '',
      heightRawRow: height != null ? String(height) : '',
      barcodes,
      barcodesInput: barcodes.join(', '),
      _saving: false,
      _msg: '',
    };
    base._orig = {
      pages: base.pages,
      readingStatus: base.readingStatus,
      topBook: base.topBook,
      widthMM: base.widthMM,
      heightMM: base.heightMM,
      barcodesInput: base.barcodesInput,
    };
    return base;
  }

  function splitBarcodes(input) {
    return input
      .split(/[\s,;]+/)
      .map((s) => s.trim())
      .filter(Boolean);
  }

  function updateRow(id, updater) {
    setResults((rows) => rows.map((r) => (r.id === id ? updater({ ...r }) : r)));
  }

  async function runSearch() {
    setSearchLoading(true);
    setSearchError('');
    try {
      const params = new URLSearchParams();
      if (sAuthor) params.set('author', sAuthor);
      if (sPublisher) params.set('publisher', sPublisher);
      if (sTitle) params.set('title', sTitle);
      if (sBarcode) params.set('barcode', sBarcode);
      if (sReadingStatus) params.set('readingStatus', sReadingStatus);
      if (sTopBook) params.set('topBook', sTopBook);
      if (sLimit) params.set('limit', String(sLimit));

      const res = await fetch('/api/register/books?' + params.toString());
      if (!res.ok) throw new Error(`Suche fehlgeschlagen: ${res.status}`);

      const data = await res.json().catch(() => []);
      const list = Array.isArray(data) ? data : data.items || data.results || [];
      setResults(list.map(normalizeRow));
    } catch (e) {
      setSearchError(e?.message || String(e));
    } finally {
      setSearchLoading(false);
    }
  }

  function revertRow(id) {
    updateRow(id, (r) => ({
      ...r,
      pages: r._orig.pages,
      readingStatus: r._orig.readingStatus,
      topBook: r._orig.topBook,
      widthMM: r._orig.widthMM,
      heightMM: r._orig.heightMM,
      widthRawRow: r._orig.widthMM != null ? String(r._orig.widthMM) : '',
      heightRawRow: r._orig.heightMM != null ? String(r._orig.heightMM) : '',
      barcodesInput: r._orig.barcodesInput,
      _msg: 'Zurückgesetzt',
    }));
  }

  // NEW, safer version
  async function saveRow(id) {
    // 1) mark row as saving
    setResults((rows) => rows.map((r) => (r.id === id ? { ...r, _saving: true, _msg: '' } : r)));

    // 2) snapshot current row
    const row = results.find((r) => r.id === id);
    if (!row) {
      setResults((rows) =>
        rows.map((r) => (r.id === id ? { ...r, _saving: false, _msg: 'Nicht gefunden' } : r)),
      );
      return;
    }

    const orig = row._orig || {};
    const payload = {};

    if (row.pages !== orig.pages && row.pages !== '') {
      payload.pages = Number(row.pages);
    }
    if (row.readingStatus !== orig.readingStatus) {
      payload.readingStatus = row.readingStatus;
    }
    if (row.topBook !== orig.topBook) {
      payload.topBook = !!row.topBook;
    }

    // parse width/height raw → mm
    const parsedW = parseDimensionToMM(row.widthRawRow);
    const parsedH = parseDimensionToMM(row.heightRawRow);

    const widthChanged = (parsedW || null) !== (orig.widthMM || null);
    const heightChanged = (parsedH || null) !== (orig.heightMM || null);
    if (widthChanged) payload.width = parsedW || null;
    if (heightChanged) payload.height = parsedH || null;

    // barcodes: replace set if changed
    const barcodesNormalized = splitBarcodes(row.barcodesInput);
    const origBarcodesNormalized = splitBarcodes(orig.barcodesInput || '');
    const sameLen = barcodesNormalized.length === origBarcodesNormalized.length;
    const sameSet = sameLen && barcodesNormalized.every((b, i) => b === origBarcodesNormalized[i]);
    if (!sameSet) payload.barcodes = barcodesNormalized;

    // 3) no changes → reset saving and exit
    if (Object.keys(payload).length === 0) {
      setResults((rows) =>
        rows.map((r) => (r.id === id ? { ...r, _saving: false, _msg: 'Keine Änderungen' } : r)),
      );
      return;
    }

    try {
      const res = await fetch(`/api/register/books/${id}`, {
        method: 'PATCH',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!res.ok) {
        throw new Error(`Update fehlgeschlagen: ${res.status}`);
      }

      // 4) success: clear _saving, update _orig
      setResults((rows) =>
        rows.map((r) => {
          if (r.id !== id) return r;

          const nextWidthMM = widthChanged ? parsedW || null : r.widthMM;
          const nextHeightMM = heightChanged ? parsedH || null : r.heightMM;
          const nextBarcodesInput = !sameSet ? barcodesNormalized.join(', ') : r.barcodesInput;

          return {
            ...r,
            _saving: false,
            _msg: 'Gespeichert',
            widthMM: nextWidthMM,
            heightMM: nextHeightMM,
            barcodesInput: nextBarcodesInput,
            _orig: {
              pages: r.pages,
              readingStatus: r.readingStatus,
              topBook: r.topBook,
              widthMM: nextWidthMM,
              heightMM: nextHeightMM,
              barcodesInput: nextBarcodesInput,
            },
          };
        }),
      );
    } catch (e) {
      // 5) error: clear _saving and show error
      setResults((rows) =>
        rows.map((r) => (r.id === id ? { ...r, _saving: false, _msg: `Fehler: ${e.message}` } : r)),
      );
    }
  }

  return (
    <div
      style={{
        fontFamily: 'system-ui, sans-serif',
        padding: '2rem',
        maxWidth: 900,
        margin: '0 auto',
      }}
    >
      <h1>RxLog – Buch registrieren</h1>

      <form onSubmit={onSubmit} className="grid" style={{ gap: '0.75rem', maxWidth: 720 }}>
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

      {/* --- Analytics section --- */}
      <details style={{ marginTop: '1.5rem' }}>
        <summary style={{ fontSize: 18, cursor: 'pointer', userSelect: 'none' }}>
          📊 Analytics – Top Authors
        </summary>

        <div
          style={{
            marginTop: '0.75rem',
            background: '#fafbfc',
            border: '1px solid #e5e7eb',
            borderRadius: 10,
            padding: '1rem',
          }}
        >
          <div style={{ display: 'flex', gap: '1rem', alignItems: 'center', flexWrap: 'wrap' }}>
            <label>
              <input type="checkbox" checked={aFinished} onChange={(e) => setAFinished(e.target.checked)} /> finished
            </label>

            <label>
              <input
                type="checkbox"
                checked={aAbandoned}
                onChange={(e) => setAAbandoned(e.target.checked)}
              />{' '}
              abandoned
            </label>

            <label>
              Limit{' '}
              <input
                type="number"
                min={1}
                max={100}
                value={aLimit}
                onChange={(e) => setALimit(Number(e.target.value))}
                style={{ width: 80 }}
              />
            </label>

            <button type="button" onClick={loadTopAuthors} disabled={analyticsLoading}>
              {analyticsLoading ? 'Lade…' : 'Laden'}
            </button>
          </div>

          {analyticsError && <div style={{ color: '#b00020', marginTop: 10 }}>Fehler: {analyticsError}</div>}

          {topAuthors.length > 0 && (
            <table style={{ width: '100%', marginTop: 12, borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  <th align="left">Author</th>
                  <th align="right">Finished</th>
                  <th align="right">Abandoned</th>
                  <th align="right">Total</th>
                  <th align="right">Pages</th>
                </tr>
              </thead>
              <tbody>
                {topAuthors.map((a) => (
                  <tr key={a.author}>
                    <td>{a.author}</td>
                    <td align="right">{a.finished}</td>
                    <td align="right">{a.abandoned}</td>
                    <td align="right">
                      <b>{a.total}</b>
                    </td>
                    <td align="right">{a.pages}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {!analyticsLoading && !analyticsError && topAuthors.length === 0 && (
            <div style={{ color: '#666', marginTop: 10 }}>Noch keine Daten geladen.</div>
          )}
        </div>
      </details>

      {/* --- Sync Issues section --- */}
      <details style={{ marginTop: '1.5rem' }}>
        <summary style={{ fontSize: 18, cursor: 'pointer', userSelect: 'none' }}>
          ⚠️ Sync Issues
        </summary>

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

      {/* --- Search & Update section --- */}
      <details open={adminOpen} onToggle={(e) => setAdminOpen(e.target.open)} style={{ marginTop: '2rem' }}>
        <summary style={{ fontSize: 18, cursor: 'pointer', userSelect: 'none' }}>
          🔎 Search & Update
        </summary>

        <div
          style={{
            marginTop: '0.75rem',
            background: '#fafbfc',
            border: '1px solid #e5e7eb',
            borderRadius: 10,
            padding: '1rem',
          }}
        >
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: '0.5rem',
              marginBottom: '0.5rem',
            }}
          >
            <label>
              Autor
              <input value={sAuthor} onChange={(e) => setSAuthor(e.target.value)} />
            </label>
            <label>
              Verlag
              <input value={sPublisher} onChange={(e) => setSPublisher(e.target.value)} />
            </label>
            <label>
              Titel (Stichwort)
              <input value={sTitle} onChange={(e) => setSTitle(e.target.value)} />
            </label>
            <label>
              Barcode
              <input value={sBarcode} onChange={(e) => setSBarcode(e.target.value)} />
            </label>
            <label>
              Lesestatus
              <select value={sReadingStatus} onChange={(e) => setSReadingStatus(e.target.value)}>
                <option value="">-- egal --</option>
                <option value="in_progress">In Bearbeitung</option>
                <option value="finished">Fertig gelesen</option>
                <option value="abandoned">Vorzeitig beendet</option>
              </select>
            </label>
            <label>
              Top-Buch
              <select value={sTopBook} onChange={(e) => setSTopBook(e.target.value)}>
                <option value="">-- egal --</option>
                <option value="true">nur Top</option>
                <option value="false">ohne Top</option>
              </select>
            </label>
            <label>
              Limit
              <input type="number" min={1} max={200} value={sLimit} onChange={(e) => setSLimit(Number(e.target.value))} />
            </label>
          </div>

          <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
            <button type="button" onClick={runSearch} disabled={searchLoading}>
              {searchLoading ? 'Suche…' : 'Suchen'}
            </button>
            <button
              type="button"
              onClick={() => {
                setSAuthor('');
                setSPublisher('');
                setSTitle('');
                setSBarcode('');
                setSReadingStatus('');
                setSTopBook('');
                setSLimit(20);
                setResults([]);
                setSearchError('');
              }}
            >
              Zurücksetzen
            </button>
          </div>

          {searchError && <div style={{ color: '#b00020', marginBottom: 8 }}>Fehler: {searchError}</div>}

          {results.length > 0 && (
            <div style={{ fontSize: 12, color: '#555', marginBottom: 6 }}>{results.length} Ergebnis(se)</div>
          )}

          {results.length > 0 ? (
            <div style={{ display: 'grid', gap: '0.5rem' }}>
              {results.map((row) => (
                <div
                  key={row.id}
                  style={{
                    border: '1px solid #e5e7eb',
                    borderRadius: 8,
                    padding: '0.75rem',
                    background: '#fff',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 12 }}>
                    <div style={{ fontWeight: 600 }}>
                      {row.author || '—'} · <span style={{ color: '#666' }}>{row.publisher || '—'}</span>
                    </div>
                    <div style={{ fontSize: 12, color: '#666' }}>ID: {row.id}</div>
                  </div>

                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(6, 1fr)',
                      gap: '0.5rem',
                      marginTop: 8,
                    }}
                  >
                    <label>
                      Seiten
                      <input
                        type="number"
                        min={1}
                        value={row.pages ?? ''}
                        onChange={(e) => updateRow(row.id, (r) => ({ ...r, pages: e.target.value }))}
                      />
                    </label>

                    <label>
                      Status
                      <select
                        value={row.readingStatus}
                        onChange={(e) => updateRow(row.id, (r) => ({ ...r, readingStatus: e.target.value }))}
                      >
                        <option value="in_progress">In Bearbeitung</option>
                        <option value="finished">Fertig</option>
                        <option value="abandoned">Abgebrochen</option>
                      </select>
                    </label>

                    <label>
                      Top-Buch
                      <input
                        type="checkbox"
                        checked={!!row.topBook}
                        onChange={(e) => updateRow(row.id, (r) => ({ ...r, topBook: e.target.checked }))}
                      />
                    </label>

                    <label>
                      Breite (mm/cm)
                      <input
                        value={row.widthRawRow}
                        onChange={(e) => updateRow(row.id, (r) => ({ ...r, widthRawRow: e.target.value }))}
                        onBlur={() =>
                          updateRow(row.id, (r) => ({ ...r, widthMM: parseDimensionToMM(r.widthRawRow) }))
                        }
                        placeholder="z. B. 105 mm / 10,5 cm / 10"
                        inputMode="decimal"
                      />
                    </label>

                    <label>
                      Höhe (mm/cm)
                      <input
                        value={row.heightRawRow}
                        onChange={(e) => updateRow(row.id, (r) => ({ ...r, heightRawRow: e.target.value }))}
                        onBlur={() =>
                          updateRow(row.id, (r) => ({ ...r, heightMM: parseDimensionToMM(r.heightRawRow) }))
                        }
                        placeholder="z. B. 190 mm / 19 cm / 19"
                        inputMode="decimal"
                      />
                    </label>

                    <label>
                      Barcodes (kommagetrennt)
                      <input
                        value={row.barcodesInput}
                        onChange={(e) => updateRow(row.id, (r) => ({ ...r, barcodesInput: e.target.value }))}
                      />
                    </label>
                  </div>

                  <div style={{ display: 'flex', gap: 8, marginTop: 8, alignItems: 'center' }}>
                    <button type="button" onClick={() => saveRow(row.id)} disabled={row._saving}>
                      {row._saving ? 'Speichern…' : 'Speichern'}
                    </button>
                    <button type="button" onClick={() => revertRow(row.id)} disabled={row._saving}>
                      Zurücksetzen
                    </button>
                    <span style={{ fontSize: 12, color: row._msg.startsWith('Fehler') ? '#b00020' : '#555' }}>
                      {row._msg}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            !searchLoading && <div style={{ color: '#666' }}>Keine Ergebnisse.</div>
          )}
        </div>
      </details>
    </div>
  );
}