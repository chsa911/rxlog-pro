package com.rxlog.register.web;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads an editable config file that defines which barcode parts are easy to confuse in practice.
 *
 * File format: one group per line, comma-separated. Example:
 *   k,kb,kg
 *   g,b
 *
 * The mapping is symmetric: each entry maps to all other entries in its group.
 *
 * The file is reloaded when it changes (mtime check). This is intentionally lightweight
 * because expected traffic is low.
 */
@Component
public class ConfusableTokenStore {

    private final Path primaryPath;
    private final Path fallbackPath;

    private final AtomicLong lastLoadedMtime = new AtomicLong(-1L);
    private volatile Map<String, List<String>> map = Map.of();

    public ConfusableTokenStore(
            @Value("${CONFUSABLE_TOKENS_FILE:/config/confusable-tokens.txt}") String file) {
        this.primaryPath = Paths.get(file);
        this.fallbackPath = Paths.get("config", "confusable-tokens.txt");
    }

    /** Returns configured alternatives for the given key (token or full barcode), lowercase. */
    public List<String> alternatives(String key) {
        if (key == null) return List.of();
        reloadIfChanged();
        return map.getOrDefault(key.trim().toLowerCase(Locale.ROOT), List.of());
    }

    private void reloadIfChanged() {
        Path path = Files.exists(primaryPath) ? primaryPath : fallbackPath;
        if (!Files.exists(path)) return;

        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (mtime == lastLoadedMtime.get()) return;

            Map<String, LinkedHashSet<String>> tmp = new HashMap<>();

            for (String line : Files.readAllLines(path)) {
                String s = line.trim().toLowerCase(Locale.ROOT);
                if (s.isEmpty() || s.startsWith("#")) continue;

                String[] parts = s.split(",");
                List<String> items = new ArrayList<>();
                for (String p : parts) {
                    String t = p.trim();
                    if (!t.isEmpty()) items.add(t);
                }
                if (items.size() < 2) continue;

                for (String item : items) {
                    tmp.putIfAbsent(item, new LinkedHashSet<>());
                    for (String other : items) {
                        if (!other.equals(item)) tmp.get(item).add(other);
                    }
                }
            }

            Map<String, List<String>> built = new HashMap<>();
            for (var e : tmp.entrySet()) {
                built.put(e.getKey(), List.copyOf(e.getValue()));
            }

            map = built;
            lastLoadedMtime.set(mtime);
        } catch (Exception ignored) {
            // keep previous map
        }
    }
}
