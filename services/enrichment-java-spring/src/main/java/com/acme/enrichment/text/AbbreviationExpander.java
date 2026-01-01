package com.acme.enrichment.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class AbbreviationExpander {

    private final Map<String, String> authorMap;
    private final Map<String, String> publisherMap;

    public AbbreviationExpander(ObjectMapper om) {
        this.authorMap = loadCsv("abbr/authors.csv");
        this.publisherMap = loadCsv("abbr/publishers.csv");
    }

    private Map<String, String> loadCsv(String path) {
        Map<String, String> map = new HashMap<>();
        try (var in = new ClassPathResource(path).getInputStream();
             var br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String header = br.readLine(); // skip header
            if (header == null) return map;

            String line;
            while ((line = br.readLine()) != null) {
                // expected columns: type,abbr_raw,abbr_norm,full
                // NOTE: if "full" contains commas, you must use a proper CSV parser.
                String[] parts = line.split(",", 4);
                if (parts.length < 4) continue;

                String abbrNorm = parts[2].trim().toLowerCase(Locale.ROOT);
                String full = parts[3].trim();

                if (!abbrNorm.isEmpty() && !full.isEmpty()) {
                    map.put(abbrNorm, full);
                }
            }
            return map;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load abbrev csv: " + path, e);
        }
    }

    /**
     * Strict rule: expand ONLY if the whole cell is exactly one abbreviation token like "k."
     * (letters/digits + trailing dot). Do NOT expand inside "anton, von" or "k. konsalik".
     */
    public String expandAuthor(String s) {
        return expandWholeCellOnly(s, authorMap);
    }

    /**
     * Strict rule: expand ONLY if the whole cell is exactly one abbreviation token like "dk."
     */
    public String expandPublisher(String s) {
        return expandWholeCellOnly(s, publisherMap);
    }

    private String expandWholeCellOnly(String s, Map<String, String> map) {
        if (s == null) return null;

        String t = s.replace('\u00A0', ' ').trim();
        if (t.isEmpty()) return t;

        // Only allow a single token: letters/digits + "." (e.g. "k.", "dk.", "scz.")
        // Anything with commas/spaces/multiple tokens will not be changed.
        if (!t.matches("(?i)^[\\p{L}\\p{Nd}]+\\.$")) {
            return s;
        }

        String key = t.toLowerCase(Locale.ROOT);
        String repl = map.get(key);
        return (repl != null && !repl.isBlank()) ? repl : s;
    }
}