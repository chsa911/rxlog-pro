package com.rxlog.register.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mobile")
public class MobileSyncController {

    private final MobileSyncService service;

    public MobileSyncController(MobileSyncService service) {
        this.service = service;
    }

    @PostMapping("/sync")
    public ResponseEntity<MobileSyncResponse> sync(@RequestBody MobileSyncRequest req) {
        return ResponseEntity.ok(service.sync(req));
    }
}