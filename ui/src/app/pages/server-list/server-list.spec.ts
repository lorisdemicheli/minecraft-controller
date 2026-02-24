import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ServerList } from './server-list';

describe('ServerList', () => {
  let component: ServerList;
  let fixture: ComponentFixture<ServerList>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ServerList]
    })
    .compileComponents();

    fixture = TestBed.createComponent(ServerList);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
