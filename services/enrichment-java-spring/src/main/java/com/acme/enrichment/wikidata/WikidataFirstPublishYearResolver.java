package com.acme.enrichment.wikidata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

@Component
public class WikidataFirstPublishYearResolver {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient wc = WebClient.builder().baseUrl("https://www.wikidata.org").build();
    private final ObjectMapper om = new ObjectMapper();

    public Optional<Integer> resolve(String titleSnippet, String author) {
        if (titleSnippet == null || titleSnippet.isBlank()) return Optional.empty();

        String search = (author != null && !author.isBlank())
                ? (titleSnippet + " " + author)
                : titleSnippet;

        // 1) search QIDs
        String searchJson = wc.get()
                .uri(uri -> uri.path("/w/api.php")
                        .queryParam("action", "wbsearchentities")
                        .queryParam("format", "json")
                        .queryParam("language", "de")
                        .queryParam("type", "item")
                        .queryParam("limit", "10")
                        .queryParam("search", search)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);

        if (searchJson == null || searchJson.isBlank()) return Optional.empty();

        try {
            JsonNode root = om.readTree(searchJson);
            JsonNode arr = root.get("search");
            if (arr == null || !arr.isArray()) return Optional.empty();

            for (JsonNode n : arr) {
                String qid = n.path("id").asText(null);
                if (qid == null || !qid.startsWith("Q")) continue;

                Optional<Integer> year = fetchP577Year(qid);
                if (year.isPresent()) return year;
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Integer> fetchP577Year(String qid) {
        String entityJson = wc.get()
                .uri(uri -> uri.path("/w/api.php")
                        .queryParam("action", "wbgetentities")
                        .queryParam("format", "json")
                        .queryParam("ids", qid)
                        .queryParam("props", "claims")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);

        if (entityJson == null || entityJson.isBlank()) return Optional.empty();

        try {
            JsonNode root = om.readTree(entityJson);
            JsonNode ent = root.path("entities").path(qid);
            JsonNode p577 = ent.path("claims").path("P577");
            if (!p577.isArray()) return Optional.empty();

            Integer best = null;
            for (JsonNode c : p577) {
                String time = c.path("mainsnak").path("datavalue").path("value").path("time").asText(null);
                Integer y = parseYear(time);
                if (y == null) continue;
                if (best == null || y < best) best = y; // earliest
            }
            return Optional.ofNullable(best);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Integer parseYear(String time) {
        if (time == null || time.length() < 5) return null;
        try {
            String s = (time.startsWith("+") || time.startsWith("-")) ? time.substring(1) : time;
            return Integer.parseInt(s.substring(0, 4));
        } catch (Exception e) {
            return null;
        }
    }
}