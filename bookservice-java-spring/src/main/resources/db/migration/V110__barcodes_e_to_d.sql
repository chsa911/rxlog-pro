DELETE FROM book_barcodes eb
WHERE eb.barcode LIKE 'e%'
  AND EXISTS (
    SELECT 1 FROM book_barcodes db
    WHERE db.barcode = ('d' || substr(eb.barcode, 2))
  );

UPDATE book_barcodes
SET barcode = 'd' || substr(barcode, 2)
WHERE barcode LIKE 'e%';