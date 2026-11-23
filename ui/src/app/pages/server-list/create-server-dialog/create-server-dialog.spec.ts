import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { CreateServerDialog } from './create-server-dialog';
import { ServerInstanceDto } from '../../../core/api-client/model/server-instance-dto';
import { testProviders } from '../../../testing/providers';

describe('CreateServerDialog', () => {
  let fixture: ComponentFixture<CreateServerDialog>;
  let component: CreateServerDialog;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CreateServerDialog],
      providers: testProviders(),
    }).compileComponents();
    fixture = TestBed.createComponent(CreateServerDialog);
    fixture.componentRef.setInput('existingNames', ['alpha']);
    fixture.componentRef.setInput('curseforgeEnabled', false);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  it('validates the first step before going on', () => {
    component.name.set('alpha');
    component.eula.set(true);
    expect(component.nameError()).toContain('Esiste già');
    expect(component.canProceed()).toBe(false);

    component.name.set('beta');
    expect(component.canProceed()).toBe(true);
    component.selectType(ServerInstanceDto.TypeEnum.Curseforge);
    expect(component.canProceed()).toBe(false); // CurseForge not configured
  });

  it('needs a version for vanilla servers and a selection for modpacks', () => {
    component.selectType(ServerInstanceDto.TypeEnum.Vanilla);
    expect(component.canProceedStep3()).toBe(false);
    component.version.set('1.21.4');
    expect(component.canProceedStep3()).toBe(true);
    component.selectType(ServerInstanceDto.TypeEnum.Modrinth);
    expect(component.canProceedStep3()).toBe(false);
  });

  it('walks through the steps', () => {
    component.name.set('beta');
    component.eula.set(true);
    component.next();
    expect(component.step()).toBe(2);
    component.next();
    expect(component.step()).toBe(3); // version still empty: blocked here
    component.next();
    expect(component.step()).toBe(3);
    component.version.set('1.21.4');
    component.next();
    expect(component.step()).toBe(4);
    component.back();
    expect(component.step()).toBe(3);
  });

  it('shows the backend error inside the dialog and stays open', () => {
    const closed = vi.fn();
    component.closed.subscribe(closed);
    component.name.set('beta');
    component.eula.set(true);
    component.version.set('1.21.4');
    component.create();
    expect(component.creating()).toBe(true);
    http.expectOne({ method: 'POST', url: 'http://api/servers' }).flush(
      { status: 409, message: 'Not enough free space on the data volume' },
      { status: 409, statusText: 'Conflict' },
    );
    expect(component.creating()).toBe(false);
    expect(component.createError()).toBe('Not enough free space on the data volume');
    expect(closed).not.toHaveBeenCalled();
  });

  it('emits the name of the created server', () => {
    const created = vi.fn();
    component.created.subscribe(created);
    component.name.set('beta');
    component.eula.set(true);
    component.version.set('1.21.4');
    component.create();
    http.expectOne({ method: 'POST', url: 'http://api/servers' }).flush({ name: 'beta' });
    expect(created).toHaveBeenCalledWith('beta');
  });

  it('searches CurseForge through the backend without any key', () => {
    component.curseforgeQuery.set('atm');
    component.searchCurseforge();
    const req = http.expectOne((r) => r.url === 'http://api/integrations/curseforge/modpacks');
    expect(req.request.headers.has('x-api-key')).toBe(false);
    expect(req.request.params.get('query')).toBe('atm');
    req.flush([{ id: 1, name: 'ATM', summary: '', downloadCount: 1, websiteUrl: 'https://www.curseforge.com/minecraft/modpacks/atm' }]);
    expect(component.curseforgeResults().length).toBe(1);
  });
});
