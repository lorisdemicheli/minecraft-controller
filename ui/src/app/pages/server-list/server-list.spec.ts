import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { ServerList } from './server-list';
import { Notifications } from '../../core/notifications/notifications';
import { testProviders } from '../../testing/providers';

describe('ServerList', () => {
  let fixture: ComponentFixture<ServerList>;
  let component: ServerList;
  let http: HttpTestingController;
  const notifications = { error: vi.fn(), info: vi.fn() };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ServerList],
      providers: [...testProviders(), { provide: Notifications, useValue: notifications }],
    }).compileComponents();
    fixture = TestBed.createComponent(ServerList);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne('http://api/servers').flush([
      { name: 'alpha', type: 'VANILLA', state: 'RUNNING', cpu: 1000, memory: 2048 },
      { name: 'beta', type: 'PLUGIN', state: 'STOPPED', cpu: 500, memory: 1024 },
    ]);
    http.expectOne('http://api/integrations').flush({ curseforgeEnabled: false });
    fixture.detectChanges();
  });

  it('shows the servers, the totals and the integration status', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('.server').length).toBe(2);
    expect(component.totals()).toMatchObject({ total: 2, online: 1, cpu: 1500, memory: 3072 });
    expect(component.curseforgeEnabled()).toBe(false);
  });

  it('filters by name', () => {
    component.query.set('bet');
    expect(component.visible().map((s) => s.name)).toEqual(['beta']);
  });

  it('opens the creation dialog', () => {
    component.dialogOpen.set(true);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('app-create-server-dialog')).not.toBeNull();
  });

  it('deletes after confirmation and moves the files to the trash by default', () => {
    component.askDelete({ name: 'alpha' }, new Event('click'));
    expect(component.deleteData()).toBe(true);
    component.confirmDelete();
    const req = http.expectOne((r) => r.method === 'DELETE' && r.url === 'http://api/servers/alpha');
    expect(req.request.params.get('deleteData')).toBe('true');
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(notifications.info).toHaveBeenCalledWith('Server alpha eliminato');
    expect(component.deleteTarget()).toBeNull();
    http.expectOne('http://api/servers').flush([]);
  });
});
