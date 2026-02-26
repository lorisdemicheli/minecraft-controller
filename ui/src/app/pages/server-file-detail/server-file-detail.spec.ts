import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ServerFileDetail } from './server-file-detail';

describe('ServerFileDetail', () => {
  let component: ServerFileDetail;
  let fixture: ComponentFixture<ServerFileDetail>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ServerFileDetail]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ServerFileDetail);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
