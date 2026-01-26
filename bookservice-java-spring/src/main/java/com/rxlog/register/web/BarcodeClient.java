package com.rxlog.register.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class BarcodeClient {

    private final RestTemplate rest;

    public BarcodeClient(RestTemplateBuilder builder) {
        // Inside docker, "barcodes-go" is the hostname of the Go service
        this.rest = builder.rootUri("http://barcodes-go:8082").build();
    }

    /** Frees the barcode in the in-memory generator pool (existing behavior). */
    public void release(String code) {
        if (code == null || code.isBlank()) return;
        try {
            System.out.println("Releasing barcode via Go service: " + code);
            rest.postForEntity("/api/barcodes/release", Map.of("code", code.trim()), Void.class);
        } catch (Exception ex) {
            ex.printStackTrace(); // we WANT to see errors for now
        }
    }

    /** Writes start of usage interval to history table (barcode.barcode_usage). */
    public void usageAssign(String barcode, String bookId, Instant usedFrom) {
        if (barcode == null || barcode.isBlank()) return;
        if (bookId == null || bookId.isBlank()) return;
        if (usedFrom == null) usedFrom = Instant.now();

        try {
            rest.postForEntity(
                    "/api/barcodes/usage/assign",
                    Map.of(
                            "barcode", barcode.trim(),
                            "bookId", bookId.trim(),
                            "usedFrom", usedFrom.toString()
                    ),
                    Void.class);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** Closes usage interval (sets used_to + reason). */
    public void usageRelease(String barcode, String bookId, Instant usedTo, String reason) {
        if (barcode == null || barcode.isBlank()) return;
        if (bookId == null || bookId.isBlank()) return;
        if (usedTo == null) usedTo = Instant.now();
        if (reason == null) reason = "released";

        try {
            rest.postForEntity(
                    "/api/barcodes/usage/release",
                    Map.of(
                            "barcode", barcode.trim(),
                            "bookId", bookId.trim(),
                            "usedTo", usedTo.toString(),
                            "reason", reason
                    ),
                    Void.class);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}