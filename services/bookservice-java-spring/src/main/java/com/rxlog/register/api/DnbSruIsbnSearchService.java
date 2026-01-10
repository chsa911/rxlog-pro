package com.rxlog.register.api;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

@Component
public class DnbSruIsbnSearchService {

    private static final Pattern ISBN13 = Pattern.compile("\\b97[89]\\d{10}\\b");

    private final boolean enabled;
    private final String baseUrl;
    private final int maxRecords;
    private final int timeoutMs;

    private final HttpClient http;

    public DnbSruIsbnSearchService(
            @Value("${dnb.enabled:true}") boolean enabled,
            @Value("${dnb.baseUrl:https://services.dnb.de/sru/dnb}") String baseUrl,
            @Value("${dnb.maxRecords:8}") int maxRecords,
            @Value("${dnb.timeoutMs:5000}") int timeoutMs
    ) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.maxRecords = maxRecords;
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
    }

    public List<IsbnCandidate> search(String author, String title, String publisher, int limit) {
        if (!enabled) return List.of();
        if (limit <= 0) limit = 5;
        if (limit > 20) limit = 20;

        String cql = buildCql(author, title, publisher);

        String uri =
                baseUrl
                        + "?"
                        + "version=1.1"
                        + "&operation=searchRetrieve"
                        + "&maximumRecords="
                        + maxRecords
                        + "&recordSchema=oai_dc"
                        + "&query="
                        + url(cql);

        String xml = fetch(uri);
        if (xml == null || xml.isBlank()) return List.of();

        return parseCandidates(xml, limit);
    }

    private String fetch(String uri) {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(uri))
                            .timeout(Duration.ofMillis(timeoutMs))
                            .GET()
                            .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) return null;
            return res.body();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<IsbnCandidate> parseCandidates(String xml, int limit) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);

            Document doc =
                    dbf.newDocumentBuilder()
                            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            XPath xp = XPathFactory.newInstance().newXPath();

            NodeList records =
                    (NodeList) xp.evaluate("//*[local-name()='record']", doc, XPathConstants.NODESET);

            List<IsbnCandidate> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            for (int i = 0; i < records.getLength(); i++) {
                Node r = records.item(i);

                String title = firstText(xp, r, ".//*[local-name()='title']");
                String creator = firstText(xp, r, ".//*[local-name()='creator']");
                String pub = firstText(xp, r, ".//*[local-name()='publisher']");

                String isbn13 = firstIsbn13InRecord(xp, r);
                if (isbn13 == null) continue;

                if (seen.add(isbn13)) {
                    out.add(new IsbnCandidate(isbn13, title, creator, pub));
                    if (out.size() >= limit) break;
                }
            }

            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String firstText(XPath xp, Node scope, String expr) throws XPathExpressionException {
        Node n = (Node) xp.evaluate(expr, scope, XPathConstants.NODE);
        if (n == null) return "";
        String t = n.getTextContent();
        return t == null ? "" : t.trim();
    }

    private static String firstIsbn13InRecord(XPath xp, Node scope) throws XPathExpressionException {
        NodeList ids =
                (NodeList) xp.evaluate(".//*[local-name()='identifier']", scope, XPathConstants.NODESET);

        for (int i = 0; i < ids.getLength(); i++) {
            String txt = ids.item(i).getTextContent();
            if (txt == null) continue;
            Matcher m = ISBN13.matcher(txt);
            if (m.find()) return m.group();
        }

        // fallback: scan whole record text
        String all = scope.getTextContent();
        if (all != null) {
            Matcher m = ISBN13.matcher(all);
            if (m.find()) return m.group();
        }
        return null;
    }

    // Same logic as enrichment: tit=kw AND per="author" AND vlg="publisher"
    private static String buildCql(String author, String title, String publisher) {
        StringBuilder sb = new StringBuilder();

        if (title != null && !title.isBlank()) {
            for (String kw : title.trim().split("\\s+")) {
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

        // not great, but avoids empty query
        return sb.isEmpty() ? "cql.serverChoice=" + q(author == null ? "" : author) : sb.toString();
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "") + "\"";
    }

    private static String url(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}