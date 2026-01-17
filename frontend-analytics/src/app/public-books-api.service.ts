import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export type Bucket = 'top' | 'finished' | 'abandoned' | 'registered';

export interface PublicBookRow {
  author: string;
  title: string;
}

@Injectable({ providedIn: 'root' })
export class PublicBooksApiService {
  constructor(private http: HttpClient) {}

  search(params: { author?: string; title?: string; bucket: Bucket; limit?: number }): Observable<PublicBookRow[]> {
    let hp = new HttpParams()
      .set('bucket', params.bucket)
      .set('limit', String(params.limit ?? 50));

    if (params.author?.trim()) hp = hp.set('author', params.author.trim());
    if (params.title?.trim()) hp = hp.set('title', params.title.trim());

    return this.http.get<PublicBookRow[]>('/api/public/books', { params: hp });
  }
}
