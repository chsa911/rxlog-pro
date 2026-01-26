package com.rxlog.register.web;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mobile/issues")
public class MobileIssueController {

    private final MobileIssueService issueService;
    private final MobileIssueSuggestionService suggestionService;

    public MobileIssueController(MobileIssueService issueService, MobileIssueSuggestionService suggestionService) {
        this.issueService = issueService;
        this.suggestionService = suggestionService;
    }

    @GetMapping
    public ResponseEntity<List<MobileIssueDto>> list(
            @RequestParam(name = "status", defaultValue = "open") String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(issueService.listIssues(status, limit));
    }

    @GetMapping("/{issueId}/suggestions")
    public ResponseEntity<MobileIssueSuggestionsResponse> suggestions(
            @PathVariable String issueId,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        int safe = Math.min(500, Math.max(1, limit));
        return ResponseEntity.ok(suggestionService.suggestions(issueId, safe));
    }

    public record ResolveRequest(String bookId) {}

    @PostMapping("/{issueId}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable String issueId, @RequestBody ResolveRequest req) {
        if (req == null || req.bookId == null || req.bookId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        issueService.resolve(issueId, req.bookId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{issueId}/ignore")
    public ResponseEntity<Void> ignore(@PathVariable String issueId) {
        issueService.ignore(issueId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{issueId}/retry")
    public ResponseEntity<Void> retry(@PathVariable String issueId) {
        issueService.retry(issueId);
        return ResponseEntity.ok().build();
    }
}
