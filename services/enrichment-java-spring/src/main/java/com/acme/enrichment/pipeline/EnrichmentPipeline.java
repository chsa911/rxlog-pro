package com.acme.enrichment.pipeline;

import com.acme.enrichment.config.PurchaseLinkProperties;
import com.acme.enrichment.dnb.DnbSruIsbnResolver;
import com.acme.enrichment.metadata.TitleResolver;
import com.acme.enrichment.model.BookEnrichmentInput;
import com.acme.enrichment.model.BookEnrichmentPatch;
import com.acme.enrichment.text.AbbreviationExpander;
import com.acme.enrichment.util.PurchaseLinkBuilder;
import com.acme.enrichment.wikidata.WikidataFirstPublishYearResolver;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Collectors;

@Component
public class EnrichmentPipeline {

    private final PurchaseLinkProperties purchase;
    private final DnbSruIsbnResolver dnb;
    private final AbbreviationExpander abbr;
    private final WikidataFirstPublishYearResolver wikidata;
    private final TitleResolver titleResolver;

    public EnrichmentPipeline(PurchaseLinkProperties purchase,
                              DnbSruIsbnResolver dnb,
                              AbbreviationExpander abbr,
                              WikidataFirstPublishYearResolver wikidata,
                              TitleResolver titleResolver) {
        this.purchase = purchase;
        this.dnb = dnb;
        this.abbr = abbr;
        this.wikidata = wikidata;
        this.titleResolver = titleResolver;
    }

    public BookEnrichmentPatch enrich(BookEnrichmentInput in) {
        String titleSnippet = (in.titleKeywords() == null) ? null :
                in.titleKeywords().stream()
                        .sorted(Comparator.comparingInt(BookEnrichmentInput.TitleKeyword::pos))
                        .map(BookEnrichmentInput.TitleKeyword::kw)
                        .collect(Collectors.joining(" "));

        String author = abbr.expandAuthor(in.author());
        String publisher = abbr.expandPublisher(in.publisher());

        String isbn13 = normalizeIsbn(in.isbn13());
        boolean fromDnb = false;

        if (isbn13 == null) {
            isbn13 = dnb.tryResolveIsbn13(author, titleSnippet, publisher).orElse(null);
            fromDnb = (isbn13 != null);
        }

        // --- NEW: resolve full title by ISBN ---
        String fullTitle = null;
        if (isbn13 != null) {
            fullTitle = titleResolver.resolve(isbn13)
                    .map(TitleResolver.ResolvedTitle::fullTitle)
                    .orElse(null);
        }

        // Use fullTitle for building purchase/search links if available
        String titleForLinks = (fullTitle != null && !fullTitle.isBlank()) ? fullTitle : titleSnippet;

        String purchaseUrl = PurchaseLinkBuilder.eurobuchUrl(
                purchase.eurobuchBaseUrl(),
                isbn13,
                author,
                titleForLinks,
                publisher
        );

        BigDecimal confidence =
                (isbn13 == null) ? new BigDecimal("0.60") :
                        (fromDnb ? new BigDecimal("0.85") : new BigDecimal("0.95"));

        // Wikidata can work with either the snippet or full title; prefer fullTitle if we have it
        String titleForWikidata = (fullTitle != null && !fullTitle.isBlank()) ? fullTitle : titleSnippet;
        Integer firstPublishYear = wikidata.resolve(titleForWikidata, author).orElse(null);

        return new BookEnrichmentPatch(
                isbn13,
                purchase.provider(),
                purchaseUrl,
                fullTitle,          // <--- NEW field
                confidence,
                Instant.now(),
                false,
                firstPublishYear
        );
    }

    private static String normalizeIsbn(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (digits.isBlank()) return null;
        // allow ISBN-10 or ISBN-13 input; DNB resolver can still convert if needed
        if (digits.length() == 10 || digits.length() == 13) return digits;
        return digits; // keep best-effort; you can also return null here if you want strictness
    }
}