export interface BookSearchResult {
  id: string;
  author?: string | null;
  publisher?: string | null;
  pages?: number | null;
  readingStatus?: string | null;
  topBook?: boolean;
  barcodes?: string[];
}

export interface BarcodeUsageItem {
  bookId: string;
  usedFrom: string;   // ISO
  usedTo?: string | null; // ISO | null
  endReason?: string | null;
  book?: BookSearchResult; // wenn Barcode-Service Bookservice-Batch schon „enriched“
}

export interface BarcodeUsageDetails {
  barcode: string;
  usages: BarcodeUsageItem[];
}
