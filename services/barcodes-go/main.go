package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"strings"

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

	assignHandler := func(w http.ResponseWriter, r *http.Request) {
		var in nextReq
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil || in.Width <= 0 || in.Height <= 0 {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}

		for {
			code, rule, variant, pos, err := barcodeSystem.NextBarcodeCandidate(in.Width, in.Height)
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

			color := ""
			if variant != nil {
				color = variant.Color
			}
			jsonOK(w, map[string]any{
				"code":        code,
				"isAvailable": false,
				"position":    pos,
				"sizeGroup":   rule.SizeGroup,
				"prefix":      prefix,
				"color":       color,
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

	// UPDATED: verify now uses mm ints for rule/prefix selection (matches barcode_engine.go refactor)
	http.HandleFunc("/api/barcodes/verify", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Code   string `json:"code"`
			Width  int    `json:"width"`  // mm
			Height int    `json:"height"` // mm
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
		widthMm := in.Width
		heightMm := in.Height
		wCm := float64(widthMm) / 10.0
		hCm := float64(heightMm) / 10.0

		// pickRule now expects width in mm (int)
		rule, err := pickRule(barcodeSystem.rules, widthMm)
		if err != nil {
			http.Error(w, "size rule not found", http.StatusNotFound)
			return
		}

		// Accept any variant prefix for the computed position.
		expectedPrefixes := make([]string, 0, len(rule.Variants))
		matchedColor := ""
		matchedPos := ""
		for i := range rule.Variants {
			v := &rule.Variants[i]
			// choosePrefixAndPosition now expects height in mm (int)
			p, ppos := choosePrefixAndPosition(rule, v, heightMm)
			p = strings.ToLower(strings.TrimSpace(p))
			if p == "" {
				continue
			}
			expectedPrefixes = append(expectedPrefixes, p)
			if matchedPos == "" {
				matchedPos = ppos
			}
		}

		// Fallback: keep previous behavior (first variant) if no variant prefixes exist.
		expectedPrefix := ""
		pos := matchedPos
		if len(expectedPrefixes) > 0 {
			expectedPrefix = expectedPrefixes[0]
		}

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

		// prefix must match ANY allowed variant prefix
		match := false
		for i := range rule.Variants {
			v := &rule.Variants[i]
			p, _ := choosePrefixAndPosition(rule, v, heightMm)
			p = strings.ToLower(strings.TrimSpace(p))
			if p != "" && p == actualPrefix {
				match = true
				matchedColor = v.Color
				break
			}
		}
		if !match {
			ok = false
			reason = "prefix does not match any allowed variant for this size rule and position"
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
			"ok":               ok,
			"reason":           reason,
			"expectedPrefix":   expectedPrefix,
			"expectedPrefixes": expectedPrefixes,
			"actualPrefix":     actualPrefix,
			"position":         pos,
			"sizeGroup":        rule.SizeGroup,
			"color":            matchedColor,
			"widthCm":          wCm,
			"heightCm":         hCm,
		})
	})

	log.Println("barcodes service on :" + port)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}