import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { testProviders } from './testing/providers';

describe('App', () => {
  it('renders the router outlet', async () => {
    await TestBed.configureTestingModule({ imports: [App], providers: testProviders() }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });
});
