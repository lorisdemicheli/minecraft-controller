import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { MainLayout } from './main-layout';
import { Auth } from '../../core/auth/auth';
import { testProviders } from '../../testing/providers';

describe('MainLayout', () => {
  async function setup() {
    const auth = { logout: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [MainLayout],
      providers: [...testProviders(), { provide: Auth, useValue: auth }],
    }).compileComponents();
    const fixture = TestBed.createComponent(MainLayout);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController)
      .expectOne('http://api/servers')
      .flush([
        { name: 'alpha', type: 'VANILLA', state: 'RUNNING' },
        { name: 'beta', type: 'PLUGIN', state: 'STOPPED' },
      ]);
    await fixture.whenStable();
    return { auth, fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('lists the servers with their state in the sidebar', async () => {
    const { el } = await setup();
    expect([...el.querySelectorAll('.srv__name')].map((n) => n.textContent)).toEqual(['alpha', 'beta']);
    expect(el.querySelector('.srv .dot--running')).not.toBeNull();
    expect(el.querySelector('.pill')?.textContent).toContain('1/2');
  });

  it('logs out', async () => {
    const { auth, el } = await setup();
    const logout = [...el.querySelectorAll<HTMLButtonElement>('.foot button')].find((b) => b.textContent?.includes('Esci'));
    logout!.click();
    expect(auth.logout).toHaveBeenCalled();
  });

  it('switches theme', async () => {
    const { fixture, el } = await setup();
    const before = fixture.componentInstance.theme.mode();
    el.querySelector<HTMLButtonElement>('.foot button')!.click();
    expect(fixture.componentInstance.theme.mode()).not.toBe(before);
  });
});
