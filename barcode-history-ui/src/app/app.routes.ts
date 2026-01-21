import { Routes } from '@angular/router';
import { BarcodeSearchComponent } from './barcode-search/barcode-search.component';

export const routes: Routes = [
  { path: '', component: BarcodeSearchComponent },
  { path: '**', redirectTo: '' }
];
