package com.rxlog.register.web;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/register/analytics")
public class AnalyticsController {

    private final BookDao dao;

    public AnalyticsController(BookDao dao) {
        this.dao = dao;
    }

    // Example:
    // /api/register/analytics/top-authors?statuses=finished,abandoned&limit=10
    @GetMapping("/top-authors")
    public List<TopAuthorStat> topAuthors(
            @RequestParam(required = false, defaultValue = "finished,abandoned") String statuses,
            @RequestParam(required = false, defaultValue = "10") Integer limit) {

        int lim = (limit == null) ? 10 : limit;
        if (lim < 1) lim = 1;
        if (lim > 100) lim = 100;

        Set<String> allowed = Set.of("finished", "abandoned", "in_progress");
        List<String> list = new ArrayList<>();

        // allow "all"
        if (statuses != null && statuses.trim().equalsIgnoreCase("all")) {
            list = List.of(); // empty => no WHERE filter
        } else {
            for (String s : (statuses == null ? "" : statuses).split("[,\\s]+")) {
                String v = s.trim().toLowerCase();
                if (!v.isEmpty() && allowed.contains(v) && !list.contains(v)) {
                    list.add(v);
                }
            }
            // if user passed nonsense => default to finished+abandoned
            if (list.isEmpty()) list = List.of("finished", "abandoned");
        }

        return dao.topAuthors(list, lim);
    }
}