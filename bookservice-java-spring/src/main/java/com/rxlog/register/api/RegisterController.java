// services/bookservice-java-spring/src/main/java/com/rxlog/register/api/RegisterController.java
package com.rxlog.register.api;

import com.rxlog.register.service.RegisterBookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Controller für den Buch-Registrierungsfluss. */
@RestController
@RequestMapping("/api/register")
public class RegisterController {

  private final RegisterBookService service;

  public RegisterController(RegisterBookService service) {
    this.service = service;
  }

  @PostMapping("/draft")
  public ResponseEntity<CreateDraftResponse> createDraft(@RequestBody CreateDraftRequest req) {
    return ResponseEntity.ok(service.createDraft(req));
  }

  @PostMapping("/book")
  public ResponseEntity<RegisterBookResponse> registerBook(@RequestBody RegisterBookRequest req) {
    RegisterBookResponse res = service.registerBook(req);
    return ResponseEntity.ok(res);
  }
}
