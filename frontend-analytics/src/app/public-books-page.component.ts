import { Component, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Bucket, PublicBookRow, PublicBooksApiService } from './public-books-api.service';

@Component({
  standalone: true,
  selector: 'app-public-books-page',
  imports: [CommonModule, FormsModule],
  template: `
    <div class="container py-4" style="max-width: 980px;">
      <div class="zr-card p-3 p-md-4">
        <div class="d-flex flex-wrap align-items-baseline justify-content-between gap-2 mb-3">
          <h1 class="h3 m-0">Öffentliche Bücherliste</h1>
          <span class="text-muted" style="font-size: 0.95rem;">/books</span>
        </div>

        <form class="row g-2 align-items-end" (ngSubmit)="runSearch(); $event.preventDefault()">
          <div class="col-12 col-md-5">
            <label class="form-label">Autor</label>
            <input class="form-control" [(ngModel)]="author" name="author" placeholder="z. B. Fontane" />
          </div>

          <div class="col-12 col-md-5">
            <label class="form-label">Titel</label>
            <input class="form-control" [(ngModel)]="title" name="title" placeholder="z. B. Effi" />
          </div>

          <div class="col-12 col-md-auto">
            <button type="submit" class="btn zr-btn-primary w-100">
              Suchen
            </button>
          </div>
        </form>

        <div class="d-flex flex-wrap gap-2 mt-3" role="group" aria-label="Filter">
          <button type="button" class="btn zr-btn-toggle" [class.active]="bucket()==='top'" (click)="setBucket('top')">
            Zuletzt Top
          </button>
          <button type="button" class="btn zr-btn-toggle" [class.active]="bucket()==='finished'" (click)="setBucket('finished')">
            Zuletzt beendet
          </button>
          <button type="button" class="btn zr-btn-toggle" [class.active]="bucket()==='abandoned'" (click)="setBucket('abandoned')">
            Zuletzt abgebrochen
          </button>
          <button type="button" class="btn zr-btn-toggle" [class.active]="bucket()==='registered'" (click)="setBucket('registered')">
            Zuletzt registriert
          </button>
        </div>

        <div *ngIf="loading()" class="mt-3">Lade…</div>
        <div *ngIf="error()" class="mt-3 text-danger">{{ error() }}</div>

        <ul *ngIf="books().length > 0" class="list-group mt-3">
          <!--
            Entire row is clickable ("stretched-link") so the user can click author, title,
            or anywhere in the row to open the product.
          -->
          <li *ngFor="let b of books()" class="list-group-item position-relative" style="cursor: pointer;">
            <div class="d-flex align-items-center justify-content-between gap-3">
              <div class="min-w-0 pe-2">
                <strong style="text-decoration: underline;">{{ b.author }}</strong>
                <span> — </span>
                <span style="text-decoration: underline;">{{ b.title }}</span>
                <span *ngIf="b.purchaseVendor" class="badge rounded-pill text-bg-light border ms-2">
                  {{ b.purchaseVendor }}
                </span>
              </div>
              <span class="text-muted" style="font-size: 0.9rem;">↗</span>
            </div>

            <a class="stretched-link" [href]="bookLink(b)" target="_blank" rel="noopener noreferrer" aria-label="Öffnen"></a>
          </li>
        </ul>

        <div *ngIf="!loading() && books().length===0" class="mt-3 text-muted">
          Keine Treffer.
        </div>
      </div>
    </div>
  `,
  styles: []
})
export class PublicBooksPageComponent {
  author = '';
  title = '';

  bucket = signal<Bucket>('registered');
  books = signal<PublicBookRow[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);

  constructor(private api: PublicBooksApiService) {}

  /**
   * Fallback search link used when we don't have a precomputed purchaseLink.
   * Uses medimops search because it supports ISBNs + free-text and is fast.
   */
  private searchLink(q: string): string {
    const query = (q ?? '').trim();
    return 'https://www.medimops.de/produkte-C0/?fcIsSearch=1&searchparam=' + encodeURIComponent(query);
  }

  authorLink(author: string): string {
    return this.searchLink(author);
  }

  bookLink(b: PublicBookRow): string {
    const direct = (b.purchaseLink ?? '').trim();
    if (direct) return direct;
    return this.searchLink(`${b.author} ${b.title}`);
  }

  async runSearch() {
    this.loading.set(true);
    this.error.set(null);
    try {
      const rows = await firstValueFrom(this.api.search({
        author: this.author,
        title: this.title,
        bucket: this.bucket(),
        limit: 50,
      }));
      this.books.set(rows);
    } catch (e: any) {
      this.error.set(e?.message || String(e));
      this.books.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  setBucket(b: Bucket) {
    this.bucket.set(b);
    void this.runSearch();
  }
}
