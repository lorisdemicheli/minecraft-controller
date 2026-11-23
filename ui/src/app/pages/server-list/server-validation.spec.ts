import { ServerInstanceDto } from '../../core/api-client/model/server-instance-dto';
import { resourcesError, serverNameError, sourceError, versionError } from './server-validation';

describe('server validation', () => {
  it('server names follow the backend rules', () => {
    for (const ok of ['a', 'survival-1', 'x'.repeat(40)]) expect(serverNameError(ok)).toBeNull();
    for (const bad of ['', 'Survival', '1abc', 'abc-', 'a_b', 'a.b', 'x'.repeat(41), 'console', 'api']) {
      expect(serverNameError(bad)).not.toBeNull();
    }
    expect(serverNameError('alpha', ['alpha'])).toContain('Esiste già');
  });

  it('versions', () => {
    for (const ok of ['LATEST', 'snapshot', '1.21.4', '1.20', '1.21-pre1', '24w14a']) {
      expect(versionError(ok)).toBeNull();
    }
    for (const bad of ['', 'latest; rm', '1', 'v1.20']) expect(versionError(bad)).not.toBeNull();
  });

  it('resources', () => {
    expect(resourcesError(1000, 1024)).toBeNull();
    expect(resourcesError(50, 1024)).not.toBeNull();
    expect(resourcesError(1000, 256)).not.toBeNull();
  });

  it('type specific step', () => {
    const T = ServerInstanceDto.TypeEnum;
    expect(sourceError(T.Vanilla, { version: '' })).not.toBeNull();
    expect(sourceError(T.Plugin, { version: '1.21.4' })).toBeNull();
    expect(sourceError(T.Modrinth, { version: '' })).not.toBeNull();
    expect(sourceError(T.Modrinth, { version: '', modrinthProjectId: 'abc' })).toBeNull();
    expect(sourceError(T.Curseforge, { version: '', curseforgePageUrl: 'https://evil/x' })).not.toBeNull();
    expect(
      sourceError(T.Curseforge, {
        version: '',
        curseforgePageUrl: 'https://www.curseforge.com/minecraft/modpacks/atm9',
      }),
    ).toBeNull();
  });
});
