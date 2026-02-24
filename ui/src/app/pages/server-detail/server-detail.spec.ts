import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ServerDetail } from './server-detail';

describe('ServerDetail', () => {
  let component: ServerDetail;
  let fixture: ComponentFixture<ServerDetail>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ServerDetail]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ServerDetail);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
