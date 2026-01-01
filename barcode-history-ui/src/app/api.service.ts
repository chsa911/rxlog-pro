import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BookSearchResult, BarcodeUsageDetails } from './models';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class ApiService {
  constructor(private http: HttpClient) {}

  searchActive(barcode: string): Observable<BookSearchResult[]> {
    const b = barcode.trim();
    return this.http.get<BookSearchResult[]>(
      `/api/register/books?barcode=${encodeURIComponent(b)}&limit=50`
    );
  }

  // Erwartet: GET /api/barcodes/usage/details?barcode=...
  searchHistory(barcode: string): Observable<BarcodeUsageDetails> {
    const b = barcode.trim();
    return this.http.get<BarcodeUsageDetails>(
      `/api/barcodes/usage/details?barcode=${encodeURIComponent(b)}`
    );
  }
}
