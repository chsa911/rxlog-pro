package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type nextReq struct {
	Width  int `json:"width"`
	Height int `json:"height"`
}

type codeReq struct {
	Code string `json:"code"`
}

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("content-type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

// ---------- NEW: schema/table bootstrap for history -------------------------

func ensureBarcodeUsageSchema(ctx context.Context, pool *pgxpool.Pool) error {
	stmts := []string{
		`CREATE SCHEMA IF NOT EXISTS barcode;`,
		`CREATE TABLE IF NOT EXISTS barcode.barcode_usage (
			id bigserial PRIMARY KEY,
			barcode text NOT NULL,
			book_id uuid NOT NULL,
			used_from timestamptz NOT NULL,
			used_to timestamptz NULL,
			start_reason text NOT NULL DEFAULT 'assigned',
			end_reason text NULL
		);`,
		`CREATE INDEX IF NOT EXISTS idx_bu_barcode ON barcode.barcode_usage(barcode);`,
		`CREATE INDEX IF NOT EXISTS idx_bu_book ON barcode.barcode_usage(book_id);`,
		// unlimited history, but only one open interval per barcode at a time:
		`CREATE UNIQUE INDEX IF NOT EXISTS uq_bu_barcode_open
		  ON barcode.barcode_usage(barcode)
		  WHERE used_to IS NULL;`,
	}
	for _, s := range stmts {
		if _, err := pool.Exec(ctx, s); err != nil {
			return err
		}
	}
	return nil
}

// ---------- NEW: usage/details response types ------------------------------

type UsageBook struct {
	Id            string  `json:"id"`
	Author        *string `json:"author,omitempty"`
	Publisher     *string `json:"publisher,omitempty"`
	Pages         *int    `json:"pages,omitempty"`
	ReadingStatus *string `json:"readingStatus,omitempty"`
	TopBook       *bool   `json:"topBook,omitempty"`
	RegisteredAt  string  `json:"registeredAt"`
}

type UsageEntry struct {
	BookId    string    `json:"bookId"`
	UsedFrom  string    `json:"usedFrom"`
	UsedTo    *string   `json:"usedTo,omitempty"`
	EndReason *string   `json:"endReason,omitempty"`
	Source    string    `json:"source"` // active | history
	Book      UsageBook `json:"book"`
}

type UsageDetails struct {
	Barcode string       `json:"barcode"`
	Usages  []UsageEntry `json:"usages"`
}

func handleUsageDetails(pool *pgxpool.Pool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		barcode := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("barcode")))
		if barcode == "" {
			http.Error(w, "missing barcode", http.StatusBadRequest)
			return
		}

		rows, err := pool.Query(r.Context(), `
			WITH hist AS (
			  SELECT
			    lower(u.barcode) AS barcode,
			    u.book_id,
			    u.used_from,
			    u.used_to,
			    u.end_reason,
			    'history'::text AS source
			  FROM barcode.barcode_usage u
			  WHERE lower(u.barcode) = $1
			),
			act AS (
			  SELECT
			    lower(bb.barcode) AS barcode,
			    bb.book_id,
			    b.registered_at AS used_from,
			    NULL::timestamptz AS used_to,
			    NULL::text AS end_reason,
			    'active'::text AS source
			  FROM book_barcodes bb
			  JOIN books b ON b.id = bb.book_id
			  WHERE lower(bb.barcode) = $1
			),
			u AS (
			  SELECT * FROM hist
			  UNION ALL
			  SELECT * FROM act
			)
			SELECT
			  u.book_id::text,
			  u.used_from,
			  u.used_to,
			  u.end_reason,
			  u.source,
			  b.id::text,
			  b.author,
			  b.publisher,
			  b.pages,
			  b.reading_status,
			  b.top_book,
			  b.registered_at
			FROM u
			JOIN books b ON b.id = u.book_id
			ORDER BY u.used_from DESC, u.used_to DESC NULLS LAST
		`, barcode)
		if err != nil {
			http.Error(w, "db error: "+err.Error(), http.StatusInternalServerError)
			return
		}
		defer rows.Close()

		out := UsageDetails{Barcode: barcode, Usages: []UsageEntry{}}

		for rows.Next() {
			var bookId string
			var usedFrom time.Time
			var usedTo *time.Time
			var endReason *string
			var source string

			var bId string
			var author *string
			var publisher *string
			var pages *int
			var readingStatus *string
			var topBook *bool
			var registeredAt time.Time

			if err := rows.Scan(
				&bookId, &usedFrom, &usedTo, &endReason, &source,
				&bId, &author, &publisher, &pages, &readingStatus, &topBook, &registeredAt,
			); err != nil {
				http.Error(w, "db scan error: "+err.Error(), http.StatusInternalServerError)
				return
			}

			uf := usedFrom.UTC().Format(time.RFC3339)
			var ut *string
			if usedTo != nil {
				s := usedTo.UTC().Format(time.RFC3339)
				ut = &s
			}
			ra := registeredAt.UTC().Format(time.RFC3339)

			out.Usages = append(out.Usages, UsageEntry{
				BookId:    bookId,
				UsedFrom:  uf,
				UsedTo:    ut,
				EndReason: endReason,
				Source:    source,
				Book: UsageBook{
					Id:            bId,
					Author:        author,
					Publisher:     publisher,
					Pages:         pages,
					ReadingStatus: readingStatus,
					TopBook:       topBook,
					RegisteredAt:  ra,
				},
			})
		}

		if err := rows.Err(); err != nil {
			http.Error(w, "db rows error: "+err.Error(), http.StatusInternalServerError)
			return
		}

		jsonOK(w, out)
	}
}

// ---------- NEW: assign/release history endpoints --------------------------

type usageAssignReq struct {
	Barcode    string `json:"barcode"`
	BookId     string `json:"bookId"`
	UsedFrom   string `json:"usedFrom"`             // ISO-8601
	StartReason string `json:"startReason,omitempty"` // optional
}

type usageReleaseReq struct {
	Barcode    string `json:"barcode"`
	BookId     string `json:"bookId"`
	UsedTo     string `json:"usedTo"`               // ISO-8601
	EndReason  string `json:"reason,omitempty"`     // e.g. finished/abandoned/replaced
}

func parseRFC3339(s string) (time.Time, error) {
	s = strings.TrimSpace(s)
	// accept RFC3339 and RFC3339Nano
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t, nil
	}
	return time.Parse(time.RFC3339Nano, s)
}

func handleUsageAssign(pool *pgxpool.Pool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var in usageAssignReq
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		in.Barcode = strings.ToLower(strings.TrimSpace(in.Barcode))
		in.BookId = strings.TrimSpace(in.BookId)
		if in.Barcode == "" || in.BookId == "" || strings.TrimSpace(in.UsedFrom) == "" {
			http.Error(w, "barcode, bookId, usedFrom required", http.StatusBadRequest)
			return
		}
		usedFrom, err := parseRFC3339(in.UsedFrom)
		if err != nil {
			http.Error(w, "usedFrom must be ISO-8601", http.StatusBadRequest)
			return
		}
		reason := strings.TrimSpace(in.StartReason)
		if reason == "" {
			reason = "assigned"
		}

		// Insert open interval. If barcode already has an open interval, do nothing (409).
		// This targets the partial unique index uq_bu_barcode_open.
		tag, err := pool.Exec(r.Context(), `
			INSERT INTO barcode.barcode_usage (barcode, book_id, used_from, start_reason)
			VALUES ($1, $2::uuid, $3, $4)
			ON CONFLICT (barcode) WHERE used_to IS NULL
			DO NOTHING
		`, in.Barcode, in.BookId, usedFrom, reason)
		if err != nil {
			http.Error(w, "db error: "+err.Error(), http.StatusInternalServerError)
			return
		}
		if tag.RowsAffected() == 0 {
			http.Error(w, "barcode already in use (open interval exists)", http.StatusConflict)
			return
		}

		jsonOK(w, map[string]any{
			"barcode":    in.Barcode,
			"bookId":     in.BookId,
			"usedFrom":   usedFrom.UTC().Format(time.RFC3339),
			"startReason": reason,
		})
	}
}

func handleUsageRelease(pool *pgxpool.Pool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var in usageReleaseReq
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		in.Barcode = strings.ToLower(strings.TrimSpace(in.Barcode))
		in.BookId = strings.TrimSpace(in.BookId)
		if in.Barcode == "" || in.BookId == "" || strings.TrimSpace(in.UsedTo) == "" {
			http.Error(w, "barcode, bookId, usedTo required", http.StatusBadRequest)
			return
		}
		usedTo, err := parseRFC3339(in.UsedTo)
		if err != nil {
			http.Error(w, "usedTo must be ISO-8601", http.StatusBadRequest)
			return
		}
		reason := strings.TrimSpace(in.EndReason)
		if reason == "" {
			reason = "released"
		}

		tag, err := pool.Exec(r.Context(), `
			UPDATE barcode.barcode_usage
			   SET used_to = $3,
			       end_reason = $4
			 WHERE lower(barcode) = $1
			   AND book_id = $2::uuid
			   AND used_to IS NULL
		`, in.Barcode, in.BookId, usedTo, reason)
		if err != nil {
			http.Error(w, "db error: "+err.Error(), http.StatusInternalServerError)
			return
		}
		if tag.RowsAffected() == 0 {
			http.Error(w, "no open usage interval found", http.StatusNotFound)
			return
		}

		jsonOK(w, map[string]any{
			"barcode":   in.Barcode,
			"bookId":    in.BookId,
			"usedTo":    usedTo.UTC().Format(time.RFC3339),
			"endReason": reason,
		})
	}
}

// ---------- existing helpers ------------------------------------------------

func loadExistingBarcodes(ctx context.Context, pool *pgxpool.Pool) ([]string, error) {
	rows, err := pool.Query(ctx, `
		SELECT barcode
		  FROM book_barcodes
		 WHERE barcode IS NOT NULL AND barcode <> ''
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []string
	for rows.Next() {
		var b string
		if err := rows.Scan(&b); err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func barcodeExists(ctx context.Context, pool *pgxpool.Pool, code string) (bool, error) {
	code = strings.ToLower(strings.TrimSpace(code))
	if code == "" {
		return false, nil
	}

	var dummy int
	err := pool.QueryRow(ctx, `
		SELECT 1
		  FROM book_barcodes
		 WHERE lower(barcode) = $1
		 LIMIT 1
	`, code).Scan(&dummy)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	return true, nil
}

func main() {
	port := os.Getenv("SERVICE_PORT")
	if port == "" {
		port = "8082"
	}
	dburl := os.Getenv("DATABASE_URL")
	if dburl == "" {
		log.Fatal("DATABASE_URL required (e.g. postgresql://rxlog:rxlog@postgres:5432/rxlog?sslmode=disable)")
	}

	rulesFile := os.Getenv("SIZERULES_FILE")
	if rulesFile == "" {
		rulesFile = "sizerules.csv"
	}
	rankingFile := os.Getenv("BARCODE_RANKING_FILE")
	if rankingFile == "" {
		rankingFile = "barcode-ranking.txt"
	}

	ctx := context.Background()

	pool, err := pgxpool.New(ctx, dburl)
	if err != nil {
		log.Fatalf("db connect: %v", err)
	}
	defer pool.Close()

	if err := ensureBarcodeUsageSchema(ctx, pool); err != nil {
		log.Fatalf("ensure barcode usage schema: %v", err)
	}

	rules, err := loadSizeRulesCSV(rulesFile)
	if err != nil {
		log.Fatalf("load size rules: %v", err)
	}
	ranking, err := loadRanking(rankingFile)
	if err != nil {
		log.Fatalf("load ranking: %v", err)
	}

	alreadyUsed, err := loadExistingBarcodes(ctx, pool)
	if err != nil {
		log.Printf("warning: could not load existing barcodes: %v", err)
		alreadyUsed = nil
	}
	log.Printf("seeded %d existing barcodes from book_barcodes", len(alreadyUsed))

	barcodeSystem := NewBarcodeSystem(rules, ranking, alreadyUsed)

	http.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok"))
	})

	// NEW: history endpoints
	http.HandleFunc("/api/barcodes/usage/details", handleUsageDetails(pool))
	http.HandleFunc("/barcodes/usage/details", handleUsageDetails(pool))

	http.HandleFunc("/api/barcodes/usage/assign", handleUsageAssign(pool))
	http.HandleFunc("/barcodes/usage/assign", handleUsageAssign(pool))

	http.HandleFunc("/api/barcodes/usage/release", handleUsageRelease(pool))
	http.HandleFunc("/barcodes/usage/release", handleUsageRelease(pool))

	assignHandler := func(w http.ResponseWriter, r *http.Request) {
		var in nextReq
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil || in.Width <= 0 || in.Height <= 0 {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}

		for {
			code, rule, pos, err := barcodeSystem.NextBarcodeCandidate(in.Width, in.Height)
			if err != nil {
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			}

			exists, err := barcodeExists(r.Context(), pool, code)
			if err != nil {
				http.Error(w, "db error: "+err.Error(), http.StatusInternalServerError)
				return
			}
			if exists {
				barcodeSystem.MarkUsed(code)
				continue
			}

			barcodeSystem.MarkUsed(code)

			prefix := ""
			if len(code) > 3 {
				prefix = code[:len(code)-3]
			}

			jsonOK(w, map[string]any{
				"code":        code,
				"isAvailable": false,
				"position":    pos,
				"sizeGroup":   rule.SizeGroup,
				"prefix":      prefix,
				"color":       rule.Color,
				"widthCm":     float64(in.Width) / 10.0,
				"heightCm":    float64(in.Height) / 10.0,
			})
			return
		}
	}

	http.HandleFunc("/api/barcodes/assignForDimensions", assignHandler)
	http.HandleFunc("/barcodes/assignForDimensions", assignHandler)

	http.HandleFunc("/api/barcodes/commit", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})
	http.HandleFunc("/barcodes/commit", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})

	releaseHandler := func(w http.ResponseWriter, r *http.Request) {
		var in codeReq
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil || strings.TrimSpace(in.Code) == "" {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		barcodeSystem.Release(in.Code)
		jsonOK(w, map[string]any{"code": in.Code, "isAvailable": true})
	}

	http.HandleFunc("/api/barcodes/release", releaseHandler)
	http.HandleFunc("/barcodes/release", releaseHandler)

	http.HandleFunc("/api/barcodes/verify", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Code   string `json:"code"`
			Width  int    `json:"width"`
			Height int    `json:"height"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil || strings.TrimSpace(in.Code) == "" {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
		if in.Width <= 0 || in.Height <= 0 {
			http.Error(w, "width and height must be greater than zero", http.StatusBadRequest)
			return
		}

		code := strings.TrimSpace(in.Code)
		wCm := float64(in.Width) / 10.0
		hCm := float64(in.Height) / 10.0

		rule, err := pickRule(barcodeSystem.rules, wCm)
		if err != nil {
			http.Error(w, "size rule not found", http.StatusNotFound)
			return
		}

		expectedPrefix, pos := choosePrefixAndPosition(rule, hCm)

		if len(code) < 4 {
			jsonOK(w, map[string]any{
				"ok":     false,
				"reason": "code too short",
			})
			return
		}
		actualPrefix := strings.ToLower(code[:len(code)-3])
		suffix := code[len(code)-3:]

		ok := true
		reason := ""

		if actualPrefix != strings.ToLower(expectedPrefix) {
			ok = false
			reason = "prefix does not match expected size rule and position"
		} else {
			if len(suffix) != 3 {
				ok = false
				reason = "suffix must be three digits"
			} else {
				for _, ch := range suffix {
					if ch < '0' || ch > '9' {
						ok = false
						reason = "suffix must contain only digits"
						break
					}
				}
				if ok {
					if _, exists := barcodeSystem.rankIndex[suffix]; !exists {
						ok = false
						reason = "suffix not in ranking list"
					}
				}
			}
		}

		jsonOK(w, map[string]any{
			"ok":             ok,
			"reason":         reason,
			"expectedPrefix": expectedPrefix,
			"actualPrefix":   actualPrefix,
			"position":       pos,
			"sizeGroup":      rule.SizeGroup,
			"color":          rule.Color,
			"widthCm":        wCm,
			"heightCm":       hCm,
		})
	})

	log.Println("barcodes service on :" + port)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}