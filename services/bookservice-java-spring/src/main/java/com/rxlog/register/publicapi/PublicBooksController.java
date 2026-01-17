package com.rxlog.register.publicapi;

import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public")
public class PublicBooksController {

    private final PublicBooksDao dao;

    public PublicBooksController(PublicBooksDao dao) {
        this.dao = dao;
    }

    @GetMapping("/books")
    public List<PublicBookRow> books(
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String title,
            @RequestParam(required = false, defaultValue = "registered") String bucket,
            @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        PublicBooksDao.Bucket b;
        try {
            b = PublicBooksDao.Bucket.valueOf(bucket.trim().toLowerCase());
        } catch (Exception e) {
            b = PublicBooksDao.Bucket.registered;
        }

        return dao.search(author, title, b, limit);
    }
}