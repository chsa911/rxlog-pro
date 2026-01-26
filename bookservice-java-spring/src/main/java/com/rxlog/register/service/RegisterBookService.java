// services/bookservice-java-spring/src/main/java/com/rxlog/register/service/RegisterBookService.java
package com.rxlog.register.service;

import com.rxlog.register.api.CreateDraftRequest;
import com.rxlog.register.api.CreateDraftResponse;
import com.rxlog.register.api.RegisterBookRequest;
import com.rxlog.register.api.RegisterBookResponse;
import com.rxlog.register.web.BookDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application service for registering books. */
@Service
public class RegisterBookService {

  private final BookDao bookDao;

  public RegisterBookService(BookDao bookDao) {
    this.bookDao = bookDao;
  }

  /**
   * Produces a „draft“ for a new book. In this demo we don't save the draft but just returnsback
   * width and height.
   */
  public CreateDraftResponse createDraft(CreateDraftRequest req) {
    return new CreateDraftResponse(
        null, // no persisted draft in demo
        req.width(),
        req.height());
  }

  /** Writes a book to db and returns the bookId + barcode + status. */
  @Transactional
  public RegisterBookResponse registerBook(RegisterBookRequest req) {
    String bookId = bookDao.insert(req);

    System.out.println(">>> registerBook: id=" + bookId + ", barcode=" + req.barcode());

    return new RegisterBookResponse(bookId, req.barcode(), req.readingStatus());
  }
}
