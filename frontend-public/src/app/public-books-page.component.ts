import { Component, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Bucket, PublicBookRow, PublicBooksApiService } from './public-books-api.service';

@Component({
  standalone: true,
  selector: 'app-public-books-page',
  imports: [CommonModule, FormsModule],
  template: `
    <div style="max-width:900px;margin:24px auto;font-family:system-ui,sans-serif;padding:0 16px;">
      <h1>Öffentliche Bücherliste</h1>

      <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:end;margin:16px 0;">
        <label style="display:flex;flex-direction:column;gap:6px;">
          Autor
          <input [(ngModel)]="author" placeholder="z. B. Fontane" style="padding:8px;border:1px solid #ddd;border-radius:8px;" />
        </label>

        <label style="display:flex;flex-direction:column;gap:6px;">
          Titel
          <input [(ngModel)]="title" placeholder="z. B. Effi" style="padding:8px;border:1px solid #ddd;border-radius:8px;" />
        </label>

        <button (click)="runSearch()" style="padding:10px 14px;border-radius:10px;border:1px solid #ddd;background:#fff;cursor:pointer;">
          Suchen
        </button>
      </div>

      <div style="display:flex;gap:10px;flex-wrap:wrap;margin:12px 0;">
        <button (click)="setBucket('top')" [style.fontWeight]="bucket()==='top' ? '700':'400'">Zuletzt Top</button>
        <button (click)="setBucket('finished')" [style.fontWeight]="bucket()==='finished' ? '700':'400'">Zuletzt beendet</button>
        <button (click)="setBucket('abandoned')" [style.fontWeight]="bucket()==='abandoned' ? '700':'400'">Zuletzt abgebrochen</button>
        <button (click)="setBucket('registered')" [style.fontWeight]="bucket()==='registered' ? '700':'400'">Zuletzt registriert</button>
      </div>

      <div *ngIf="loading()" style="margin-top:12px;">Lade…</div>
      <div *ngIf="error()" style="margin-top:12px;color:#b00020;">{{ error() }}</div>

      <ul style="margin-top:16px;padding-left:18px;">
        <li *ngFor="let b of books()" style="margin:8px 0;">
          <b>{{ b.author }}</b> — {{ b.title }}
        </li>
      </ul>

      <div *ngIf="!loading() && books().length===0" style="margin-top:16px;color:#666;">
        Keine Treffer.
      </div>
    </div>
  `,
  styles: [`
    button{padding:8px 12px;border-radius:10px;border:1px solid #ddd;background:#fff;cursor:pointer;}
    button:hover{background:#f6f6f6;}
  `]
})
export class PublicBooksPageComponent {
  author = '';
  title = '';

  bucket = signal<Bucket>('registered');
  books = signal<PublicBookRow[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);

  constructor(private api: PublicBooksApiService) {}

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
