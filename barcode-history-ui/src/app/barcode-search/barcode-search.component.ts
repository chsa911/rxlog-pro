import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';

import { ApiService } from '../api.service';
import { BarcodeUsageDetails, BookSearchResult } from '../models';

@Component({
  selector: 'app-barcode-search',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './barcode-search.component.html',
  styleUrl: './barcode-search.component.scss',
})
export class BarcodeSearchComponent {
  barcode = '';

  loading = false;
  error?: string;

  active: BookSearchResult[] = [];
  history?: BarcodeUsageDetails;

  constructor(private api: ApiService) {}

  search() {
    const b = this.barcode.trim();
    if (!b) return;

    this.loading = true;
    this.error = undefined;
    this.active = [];
    this.history = undefined;

    forkJoin({
      active: this.api.searchActive(b),
      history: this.api.searchHistory(b),
    }).subscribe({
      next: ({ active, history }) => {
        this.active = active ?? [];
        this.history = history;
        this.loading = false;
      },
      error: (e) => {
        this.error = e?.message ?? 'Request failed';
        this.loading = false;
      },
    });
  }

  isHistoricalOnly(bookId: string): boolean {
    const activeIds = new Set(this.active.map((a) => a.id));
    return !activeIds.has(bookId);
  }

  bookTitleLine(b: BookSearchResult): string {
    const parts = [
      b.author ?? '',
      b.publisher ?? '',
      b.pages != null ? `${b.pages} pages` : '',
      b.readingStatus ?? '',
    ].filter(Boolean);
    return parts.join(' · ');
  }
}
