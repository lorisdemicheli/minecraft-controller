import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { HttpTestingController } from '@angular/common/http/testing';
import { ServerFileDetail } from './server-file-detail';
import { testProviders } from '../../testing/providers';

describe('ServerFileDetail', () => {
  let fixture: ComponentFixture<ServerFileDetail>;
  let component: ServerFileDetail;
  let http: HttpTestingController;

  const list = (path: string) => `http://api/servers/alpha/files?path=${encodeURIComponent(path)}`;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ServerFileDetail],
      providers: [
        ...testProviders(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'alpha' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ServerFileDetail);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne(list('/')).flush([
      { name: 'b.txt', type: 'FILE' },
      { name: 'world', type: 'DIRECTORY' },
      { name: 'a.yml', type: 'FILE' },
    ]);
  });

  it('lists the root directory, folders first', () => {
    expect(component.folders().map((e) => e.name)).toEqual(['world']);
    expect(component.files().map((e) => e.name)).toEqual(['a.yml', 'b.txt']);
    expect(component.joinPath('/', 'a')).toBe('/a');
    expect(component.joinPath('/a', 'b')).toBe('/a/b');
  });

  it('builds the breadcrumbs and goes up', () => {
    component.loadDir('/world/region');
    http.expectOne(list('/world/region')).flush([]);
    expect(component.breadcrumbs().map((c) => c.label)).toEqual(['alpha', 'world', 'region']);
    component.goUp();
    http.expectOne(list('/world')).flush([]);
    expect(component.currentPath()).toBe('/world');
  });

  it('rejects names that are taken or contain a slash', () => {
    component.openModal('new-file');
    component.inputValue.set('b.txt');
    expect(component.inputError()).toContain('Esiste già');
    expect(component.canConfirm()).toBe(false);
    component.inputValue.set('a/b');
    expect(component.inputError()).toBe('Nome non valido');
    component.inputValue.set('c.txt');
    expect(component.canConfirm()).toBe(true);
  });

  it('edits a text file and asks before discarding unsaved changes', () => {
    component.openEntry({ name: 'a.yml', type: 'FILE' });
    http.expectOne((r) => r.url === 'http://api/servers/alpha/files/content').flush('x: 1', {
      headers: { 'Content-Type': 'text/plain' },
    });
    expect(component.dirty()).toBe(false);

    component.editorContent.set('x: 2');
    expect(component.dirty()).toBe(true);
    component.closeEditor();
    expect(component.editorOpen()).toBe(true); // still open: waiting for the answer
    expect(component.modalMode()).toBe('discard');

    component.confirmModal();
    expect(component.editorOpen()).toBe(false);
    expect(component.modalMode()).toBe('none');
  });

  it('saves the edited content', () => {
    component.openEntry({ name: 'a.yml', type: 'FILE' });
    http.expectOne((r) => r.url === 'http://api/servers/alpha/files/content' && r.method === 'GET').flush('x: 1', {
      headers: { 'Content-Type': 'text/plain' },
    });
    component.editorContent.set('x: 2');
    component.saveEditor();
    const put = http.expectOne((r) => r.url === 'http://api/servers/alpha/files/content' && r.method === 'PUT');
    expect(put.request.body).toBe('x: 2');
    put.flush(null, { status: 200, statusText: 'OK' });
    expect(component.dirty()).toBe(false);
    http.expectOne(list('/')).flush([]); // refreshed for the new size
  });

  it('closes the editor when its file is deleted', () => {
    component.openEntry({ name: 'a.yml', type: 'FILE' });
    http.expectOne((r) => r.url === 'http://api/servers/alpha/files/content').flush('x', {
      headers: { 'Content-Type': 'text/plain' },
    });
    component.openModal('delete', { name: 'a.yml', type: 'FILE' });
    component.confirmModal();
    http.expectOne((r) => r.method === 'DELETE').flush(null, { status: 204, statusText: 'No Content' });
    expect(component.editorOpen()).toBe(false);
    http.expectOne(list('/')).flush([]);
  });
});
