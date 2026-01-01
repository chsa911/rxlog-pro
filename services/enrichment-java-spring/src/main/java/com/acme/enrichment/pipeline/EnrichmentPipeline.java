package com.acme.enrichment.pipeline;

import com.acme.enrichment.config.PurchaseLinkProperties;
import com.acme.enrichment.dnb.DnbSruIsbnResolver;
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

    public EnrichmentPipeline(PurchaseLinkProperties purchase,
                              DnbSruIsbnResolver dnb,
                              AbbreviationExpander abbr,
                              WikidataFirstPublishYearResolver wikidata) {
        this.purchase = purchase;
        this.dnb = dnb;
        this.abbr = abbr;
        this.wikidata = wikidata;
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

        String purchaseUrl = PurchaseLinkBuilder.eurobuchUrl(
                purchase.eurobuchBaseUrl(),
                isbn13,
                author,
                titleSnippet,
                publisher
        );

        BigDecimal confidence =
                (isbn13 == null) ? new BigDecimal("0.60") :
                        (fromDnb ? new BigDecimal("0.85") : new BigDecimal("0.95"));

        Integer firstPublishYear = wikidata.resolve(titleSnippet, author).orElse(null);

        return new BookEnrichmentPatch(
                isbn13,
                purchase.provider(),
                purchaseUrl,
                confidence,
                Instant.now(),
                false,
                firstPublishYear
        );
    }

    private static String normalizeIsbn(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }
}