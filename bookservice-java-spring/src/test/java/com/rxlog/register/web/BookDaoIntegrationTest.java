package com.rxlog.register.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Integration tests for BookDao barcode behaviour. */
@SpringBootTest
@Transactional
class BookDaoIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Autowired BookDao bookDao;

  @MockBean BarcodeClient barcodeClient;

  @Test
  void finishingBook_freesBarcodesAndCallsRelease() {
    // Arrange: insert a book and attach one barcode
    String bookId =
        jdbc.queryForObject(
            """
                insert into books (
                    author,
                    publisher,
                    pages,
                    title_keyword,
                    title_keyword_position,
                    title_keyword2,
                    title_keyword2_position,
                    title_keyword3,
                    title_keyword3_position,
                    width,
                    height,
                    reading_status,
                    top_book,
                    registered_at,
                    reading_status_updated_at
                )
                values (
                    'Test Author',
                    'Test Publisher',
                    100,
                    'Test',
                    1,
                    null,
                    null,
                    null,
                    null,
                    100,
                    200,
                    'in_progress',
                    false,
                    now(),
                    now()
                )
                returning id::text
                """,
            String.class);
    assertNotNull(bookId);

    jdbc.update(
        "insert into book_barcodes (book_id, barcode) values (?::uuid, ?)", bookId, "os001");

    Integer beforeCount =
        jdbc.queryForObject(
            "select count(*) from book_barcodes where book_id = ?::uuid", Integer.class, bookId);
    assertEquals(1, beforeCount);

    // Act: set reading_status to finished via BookDao
    BookUpdateRequest req = new BookUpdateRequest();
    req.setReadingStatus("finished");

    boolean changed = bookDao.partialUpdate(bookId, req);
    assertTrue(changed);

    // Assert: reading_status updated
    String status =
        jdbc.queryForObject(
            "select reading_status from books where id = ?::uuid", String.class, bookId);
    assertEquals("finished", status);

    // Assert: barcodes removed from link table
    Integer afterCount =
        jdbc.queryForObject(
            "select count(*) from book_barcodes where book_id = ?::uuid", Integer.class, bookId);
    assertEquals(0, afterCount);

    // Assert: barcodeClient.release was called for the freed barcode
    verify(barcodeClient).release("os001");
  }

  @Test
  void updatingPagesOnly_doesNotFreeBarcodesOrCallRelease() {
    // Arrange
    String bookId =
        jdbc.queryForObject(
            """
                insert into books (
                    author,
                    publisher,
                    pages,
                    title_keyword,
                    title_keyword_position,
                    title_keyword2,
                    title_keyword2_position,
                    title_keyword3,
                    title_keyword3_position,
                    width,
                    height,
                    reading_status,
                    top_book,
                    registered_at,
                    reading_status_updated_at
                )
                values (
                    'Test Author',
                    'Test Publisher',
                    100,
                    'Test',
                    1,
                    null,
                    null,
                    null,
                    null,
                    100,
                    200,
                    'in_progress',
                    false,
                    now(),
                    now()
                )
                returning id::text
                """,
            String.class);
    assertNotNull(bookId);

    jdbc.update(
        "insert into book_barcodes (book_id, barcode) values (?::uuid, ?)", bookId, "os002");

    Integer beforeCount =
        jdbc.queryForObject(
            "select count(*) from book_barcodes where book_id = ?::uuid", Integer.class, bookId);
    assertEquals(1, beforeCount);

    // Act: only change pages
    BookUpdateRequest req = new BookUpdateRequest();
    req.setPages(200);

    boolean changed = bookDao.partialUpdate(bookId, req);
    assertTrue(changed);

    // Assert: barcode link still exists
    Integer afterCount =
        jdbc.queryForObject(
            "select count(*) from book_barcodes where book_id = ?::uuid", Integer.class, bookId);
    assertEquals(1, afterCount);

    // Assert: no release call when status didn't become finished/abandoned
    verifyNoInteractions(barcodeClient);
  }
}
