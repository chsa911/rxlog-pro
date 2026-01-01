package com.acme.enrichment.dnb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DnbSruIsbnResolver {

    private static final Pattern ISBN13 = Pattern.compile("\\b97[89]\\d{10}\\b");

    private final WebClient wc;
    private final boolean enabled;
    private final int maxRecords;
    private final int timeoutMs;

    public DnbSruIsbnResolver(
            @Value("${dnb.enabled:true}") boolean enabled,
            @Value("${dnb.baseUrl:https://services.dnb.de/sru/dnb}") String baseUrl,
            @Value("${dnb.maxRecords:5}") int maxRecords,
            @Value("${dnb.timeoutMs:5000}") int timeoutMs
    ) {
        this.enabled = enabled;
        this.maxRecords = maxRecords;
        this.timeoutMs = timeoutMs;
        this.wc = WebClient.builder().baseUrl(baseUrl).build();
    }

    public Optional<String> tryResolveIsbn13(String author, String titleSnippet, String publisher) {
        if (!enabled) return Optional.empty();

        String cql = buildCql(author, titleSnippet, publisher);

        String xml = wc.get()
                .uri(uri -> uri
                        .queryParam("version", "1.1")
                        .queryParam("operation", "searchRetrieve")
                        .queryParam("maximumRecords", Integer.toString(maxRecords))
                        .queryParam("recordSchema", "oai_dc")
                        .queryParam("query", cql)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(timeoutMs));

        if (xml == null || xml.isBlank()) return Optional.empty();

        Set<String> found = new LinkedHashSet<>();
        Matcher m = ISBN13.matcher(xml);
        while (m.find()) found.add(m.group());

        return (found.size() == 1) ? Optional.of(found.iterator().next()) : Optional.empty();
    }

    private static String buildCql(String author, String titleSnippet, String publisher) {
        StringBuilder sb = new StringBuilder();

        if (titleSnippet != null && !titleSnippet.isBlank()) {
            for (String kw : titleSnippet.trim().split("\\s+")) {
                if (kw.isBlank()) continue;
                if (!sb.isEmpty()) sb.append(" AND ");
                sb.append("tit=").append(q(kw));
            }
        }
        if (author != null && !author.isBlank()) {
            if (!sb.isEmpty()) sb.append(" AND ");
            sb.append("per=").append(q(author.trim()));
        }
        if (publisher != null && !publisher.isBlank()) {
            if (!sb.isEmpty()) sb.append(" AND ");
            sb.append("vlg=").append(q(publisher.trim()));
        }

        return sb.isEmpty() ? "cql.serverChoice=" + q(author == null ? "" : author) : sb.toString();
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "") + "\"";
    }
}