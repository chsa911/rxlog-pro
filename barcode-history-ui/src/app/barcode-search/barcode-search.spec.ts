import { ComponentFixture, TestBed } from '@angular/core/testing';

import { BarcodeSearch } from './barcode-search';

describe('BarcodeSearch', () => {
  let component: BarcodeSearch;
  let fixture: ComponentFixture<BarcodeSearch>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BarcodeSearch]
    })
    .compileComponents();

    fixture = TestBed.createComponent(BarcodeSearch);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
