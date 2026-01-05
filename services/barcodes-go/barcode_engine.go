package main

import (
	"encoding/csv"
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
)

// SizeRule represents one row in sizerules.csv.
// WidthMinCm / WidthMaxCm define the size band;
// SpecialHeightsCm contains heights that get a dedicated prefix/position.
type SizeRule struct {
	SizeGroup      int
	WidthMinCm     float64
	WidthMaxCm     float64
	ThresholdCm    float64
	Variants       []SizeRuleVariant
	SpecialHeights []float64
}

// SizeRuleVariant represents one “alternative” within a size rule.
// The system will try variants in the order given (priority order).
// RankStart/RankEnd (optional) limit which suffixes are considered
// from the global ranking list.
type SizeRuleVariant struct {
	Color         string
	LowPrefix     string
	HighPrefix    string
	SpecialPrefix string
	RankStart     string
	RankEnd       string
}

func parseTokens(s string) []string {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.ToLower(strings.TrimSpace(p))
		if p == "" {
			continue
		}
		out = append(out, p)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func normalizeTokenList(tokens []string, n int) ([]string, error) {
	if n <= 0 {
		return []string{}, nil
	}
	if len(tokens) == 0 {
		return make([]string, n), nil
	}
	if len(tokens) == 1 && n > 1 {
		out := make([]string, n)
		for i := 0; i < n; i++ {
			out[i] = tokens[0]
		}
		return out, nil
	}
	if len(tokens) != n {
		return nil, fmt.Errorf("token list length mismatch: want %d, got %d", n, len(tokens))
	}
	return tokens, nil
}

func parseHeights(s string) []float64 {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ";")
	out := make([]float64, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		v, err := strconv.ParseFloat(strings.ReplaceAll(p, ",", "."), 64)
		if err == nil {
			out = append(out, v)
		}
	}
	return out
}

func loadSizeRulesCSV(path string) ([]SizeRule, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open size rules: %w", err)
	}
	defer f.Close()

	r := csv.NewReader(f)
	r.Comma = ','
	r.FieldsPerRecord = -1

	rows, err := r.ReadAll()
	if err != nil {
		return nil, fmt.Errorf("read size rules csv: %w", err)
	}
	if len(rows) < 2 {
		return nil, fmt.Errorf("size rules csv has no data")
	}

	var rules []SizeRule
	for i, row := range rows[1:] {
		if len(row) < 9 {
			return nil, fmt.Errorf("row %d: expected at least 9 columns, got %d", i+2, len(row))
		}
		sg, _ := strconv.Atoi(strings.TrimSpace(row[0]))
		wmin, _ := strconv.ParseFloat(strings.TrimSpace(row[1]), 64)
		wmax, _ := strconv.ParseFloat(strings.TrimSpace(row[2]), 64)
		th, _ := strconv.ParseFloat(strings.TrimSpace(row[4]), 64)
		colors := parseTokens(row[3])
		lowPrefixes := parseTokens(row[5])
		highPrefixes := parseTokens(row[6])
		specialPrefixes := parseTokens(row[7])

		// Optional (new) columns:
		// 9: rank_start_suffix
		// 10: rank_end_suffix
		var rankStarts, rankEnds []string
		if len(row) >= 10 {
			rankStarts = parseTokens(row[9])
		}
		if len(row) >= 11 {
			rankEnds = parseTokens(row[10])
		}

		n := 1
		n = max(n, len(colors))
		n = max(n, len(lowPrefixes))
		n = max(n, len(highPrefixes))
		n = max(n, len(specialPrefixes))
		n = max(n, len(rankStarts))
		n = max(n, len(rankEnds))

		colorsN, err := normalizeTokenList(colors, n)
		if err != nil {
			return nil, fmt.Errorf("row %d colors: %w", i+2, err)
		}
		lowN, err := normalizeTokenList(lowPrefixes, n)
		if err != nil {
			return nil, fmt.Errorf("row %d low_prefix: %w", i+2, err)
		}
		highN, err := normalizeTokenList(highPrefixes, n)
		if err != nil {
			return nil, fmt.Errorf("row %d high_prefix: %w", i+2, err)
		}
		specialN, err := normalizeTokenList(specialPrefixes, n)
		if err != nil {
			return nil, fmt.Errorf("row %d special_prefix: %w", i+2, err)
		}
		rankStartN, err := normalizeTokenList(rankStarts, n)
		if err != nil {
			return nil, fmt.Errorf("row %d rank_start_suffix: %w", i+2, err)
		}
		rankEndN, err := normalizeTokenList(rankEnds, n)
		if err != nil {
			return nil, fmt.Errorf("row %d rank_end_suffix: %w", i+2, err)
		}

		variants := make([]SizeRuleVariant, 0, n)
		for vi := 0; vi < n; vi++ {
			variants = append(variants, SizeRuleVariant{
				Color:         colorsN[vi],
				LowPrefix:     lowN[vi],
				HighPrefix:    highN[vi],
				SpecialPrefix: specialN[vi],
				RankStart:     rankStartN[vi],
				RankEnd:       rankEndN[vi],
			})
		}
		specialHeights := parseHeights(row[8])

		rules = append(rules, SizeRule{
			SizeGroup:      sg,
			WidthMinCm:     wmin,
			WidthMaxCm:     wmax,
			ThresholdCm:    th,
			Variants:       variants,
			SpecialHeights: specialHeights,
		})
	}
	return rules, nil
}

func (r SizeRule) matchesWidth(widthCm float64) bool {
	return widthCm >= r.WidthMinCm && widthCm <= r.WidthMaxCm
}

func pickRule(rules []SizeRule, widthCm float64) (*SizeRule, error) {
	for i := range rules {
		if rules[i].matchesWidth(widthCm) {
			return &rules[i], nil
		}
	}
	return nil, fmt.Errorf("no size rule for width %.1f cm", widthCm)
}

func loadRanking(path string) ([]string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("open ranking: %w", err)
	}
	lines := strings.Split(string(data), "\n")
	out := make([]string, 0, len(lines))
	for _, ln := range lines {
		s := strings.TrimSpace(ln)
		if len(s) != 3 {
			continue
		}
		if s[0] < '0' || s[0] > '9' || s[1] < '0' || s[1] > '9' || s[2] < '0' || s[2] > '9' {
			continue
		}
		out = append(out, s)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("ranking file seems empty or invalid")
	}
	return out, nil
}

type BarcodeSystem struct {
	rules     []SizeRule
	ranking   []string
	rankIndex map[string]int
	used      map[string]struct{}
}

func NewBarcodeSystem(rules []SizeRule, ranking []string, alreadyUsed []string) *BarcodeSystem {
	used := make(map[string]struct{}, len(alreadyUsed))
	for _, c := range alreadyUsed {
		c = strings.ToLower(strings.TrimSpace(c))
		if c != "" {
			used[c] = struct{}{}
		}
	}
	rankIndex := make(map[string]int, len(ranking))
	for i, s := range ranking {
		rankIndex[s] = i
	}
	return &BarcodeSystem{
		rules:     rules,
		ranking:   ranking,
		rankIndex: rankIndex,
		used:      used,
	}
}

func almostEqual(a, b float64) bool {
	return math.Abs(a-b) < 0.05
}

func isSpecialHeight(hCm float64, specials []float64) bool {
	hRounded := math.Round(hCm*10) / 10
	for _, s := range specials {
		if almostEqual(hRounded, s) {
			return true
		}
	}
	return false
}

func choosePrefixAndPosition(rule *SizeRule, v *SizeRuleVariant, heightCm float64) (prefix string, position string) {
	if isSpecialHeight(heightCm, rule.SpecialHeights) && strings.TrimSpace(v.SpecialPrefix) != "" {
		return v.SpecialPrefix, "left"
	}
	if heightCm < rule.ThresholdCm {
		return v.LowPrefix, "down"
	}
	return v.HighPrefix, "up"
}

// NextBarcodeCandidate picks the next free barcode for a given width/height
// based on the loaded size rules and current usage.
// Returns code, matching rule, position ("top"/"bottom"/"left"), or an error
// if no code or rule applies.
// isSpecialHeight checks if hCm matches one of the "special" heights
// (e.g. to force a specific edge placement).
// rankingWindow returns the list of suffixes to consider for a variant.
//
// If startSuffix/endSuffix are both set and parse as numbers (e.g. "215"),
// the window is treated as a numeric range [start, end] (inclusive), while
// preserving the global ranking order.
//
// Otherwise, the window is treated as a segment in the ranking list from
// startSuffix to endSuffix (inclusive), wrapping around the end if needed.
// If start/end are empty or not found, the full ranking is returned.
func (bs *BarcodeSystem) rankingWindow(startSuffix, endSuffix string) []string {
	startSuffix = strings.TrimSpace(startSuffix)
	endSuffix = strings.TrimSpace(endSuffix)
	if startSuffix == "" && endSuffix == "" {
		return bs.ranking
	}

	// Numeric range mode: if both boundaries are numbers and start <= end,
	// we keep the global ranking order but only keep suffixes within [start, end].
	if startSuffix != "" && endSuffix != "" {
		if startN, err1 := strconv.Atoi(startSuffix); err1 == nil {
			if endN, err2 := strconv.Atoi(endSuffix); err2 == nil && startN <= endN {
				out := make([]string, 0, len(bs.ranking))
				for _, s := range bs.ranking {
					n, err := strconv.Atoi(s)
					if err != nil {
						continue
					}
					if n >= startN && n <= endN {
						out = append(out, s)
					}
				}
				return out
			}
		}
	}

	// Fallback: treat boundaries as markers inside the ranking list (inclusive),
	// wrapping around if start is after end.
	startIdx := -1
	endIdx := -1
	for i, s := range bs.ranking {
		if s == startSuffix {
			startIdx = i
		}
		if s == endSuffix {
			endIdx = i
		}
	}
	if startIdx < 0 || endIdx < 0 {
		return bs.ranking
	}
	if startIdx <= endIdx {
		return bs.ranking[startIdx : endIdx+1]
	}
	out := make([]string, 0, (len(bs.ranking)-startIdx)+(endIdx+1))
	out = append(out, bs.ranking[startIdx:]...)
	out = append(out, bs.ranking[:endIdx+1]...)
	return out
}

func (bs *BarcodeSystem) NextBarcodeCandidate(widthMm, heightMm int) (code string, rule *SizeRule, variant *SizeRuleVariant, position string, err error) {
	if widthMm <= 0 || heightMm <= 0 {
		return "", nil, nil, "", fmt.Errorf("width and height must be greater than zero")
	}
	wCm := float64(widthMm) / 10.0
	hCm := float64(heightMm) / 10.0

	r, err := pickRule(bs.rules, wCm)
	if err != nil {
		return "", nil, nil, "", err
	}
	if len(r.Variants) == 0 {
		return "", nil, nil, "", fmt.Errorf("no variants configured for width %.1f cm", wCm)
	}

	// Try variants in order (priority), fall back to next if this variant has no stock.
	for vi := range r.Variants {
		v := &r.Variants[vi]
		prefix, pos := choosePrefixAndPosition(r, v, hCm)
		if strings.TrimSpace(prefix) == "" {
			continue
		}
		suffixes := bs.rankingWindow(v.RankStart, v.RankEnd)
		for _, suffix := range suffixes {
			candidate := prefix + suffix
			key := strings.ToLower(candidate)
			if _, exists := bs.used[key]; !exists {
				return candidate, r, v, pos, nil
			}
		}
	}

	// Build a helpful error message.
	firstV := &r.Variants[0]
	prefix, _ := choosePrefixAndPosition(r, firstV, hCm)
	if strings.TrimSpace(prefix) == "" {
		return "", nil, nil, "", fmt.Errorf("no prefix configured for width %.1f cm and height %.1f cm", wCm, hCm)
	}
	return "", nil, nil, "", fmt.Errorf("no free barcode left for any variant of sizegroup %d", r.SizeGroup)
}

func (bs *BarcodeSystem) MarkUsed(code string) {
	code = strings.ToLower(strings.TrimSpace(code))
	if code != "" {
		bs.used[code] = struct{}{}
	}
}

func (bs *BarcodeSystem) Release(code string) {
	code = strings.ToLower(strings.TrimSpace(code))
	if code == "" {
		return
	}
	delete(bs.used, code)
}
