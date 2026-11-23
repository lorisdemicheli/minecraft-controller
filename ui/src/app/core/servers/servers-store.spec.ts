import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { ServersStore } from './servers-store';
import { testProviders } from '../../testing/providers';

describe('ServersStore', () => {
  let store: ServersStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: testProviders() });
    store = TestBed.inject(ServersStore);
    http = TestBed.inject(HttpTestingController);
  });

  it('keeps the servers sorted and counts the running ones', () => {
    store.refresh();
    http.expectOne('http://api/servers').flush([
      { name: 'b', state: 'RUNNING' },
      { name: 'a', state: 'STOPPED' },
    ]);
    expect(store.servers().map((s) => s.name)).toEqual(['a', 'b']);
    expect(store.running()).toBe(1);
    expect(store.loaded()).toBe(true);
    expect(store.error()).toBeNull();
  });

  it('keeps the error inline and the previous list when the cluster is unreachable', () => {
    store.refresh();
    http.expectOne('http://api/servers').flush([{ name: 'a' }]);
    store.refresh();
    http.expectOne('http://api/servers').flush(
      { status: 503, message: 'down' },
      { status: 503, statusText: 'Service Unavailable' },
    );
    expect(store.error()).toBeTruthy();
    expect(store.servers().length).toBe(1);
  });

  it('does not start a second request while one is running', () => {
    store.refresh();
    store.refresh();
    http.expectOne('http://api/servers').flush([]);
  });
});
