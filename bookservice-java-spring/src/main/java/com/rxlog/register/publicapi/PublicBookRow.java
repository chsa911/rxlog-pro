package com.rxlog.register.publicapi;

/**
 * Row returned by /api/public/books.
 *
 * purchaseVendor/purchaseLink are optional and are filled when the book already
 * has enrichment fields (purchase_source/purchase_url) populated.
 */
public record PublicBookRow(
        String author,
        String title,
        String purchaseVendor,
        String purchaseLink
) {}