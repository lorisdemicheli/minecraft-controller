import { ServerInstanceDto } from '../../core/api-client/model/server-instance-dto';

/**
 * Immediate feedback for the creation wizard. The backend stays the authority (its message is
 * shown when it refuses something); these checks only mirror its rules to avoid useless requests.
 */

export const NAME_MAX_LENGTH = 40;
const NAME_PATTERN = /^[a-z]([a-z0-9-]{0,38}[a-z0-9])?$/;
const RESERVED_NAMES = new Set(['console', 'api']);
const VERSION_PATTERN = /^(LATEST|SNAPSHOT|[0-9]+(\.[0-9]+){1,2}(-(pre|rc)[0-9]+)?|[0-9]{2}w[0-9]{2}[a-z])$/i;

export const CPU_MIN = 100;
export const CPU_MAX = 64000;
export const MEMORY_MIN = 512;
export const MEMORY_MAX = 262144;

export function serverNameError(name: string, existing: readonly string[] = []): string | null {
  const n = name.trim();
  if (!n) return 'Il nome è obbligatorio';
  if (n.length > NAME_MAX_LENGTH) return `Massimo ${NAME_MAX_LENGTH} caratteri`;
  if (!NAME_PATTERN.test(n)) {
    return 'Solo lettere minuscole, cifre e "-": deve iniziare con una lettera e non finire con "-"';
  }
  if (RESERVED_NAMES.has(n)) return `"${n}" è un nome riservato`;
  if (existing.includes(n)) return 'Esiste già un server con questo nome';
  return null;
}

export function versionError(version: string): string | null {
  const v = version.trim();
  if (!v) return 'La versione è obbligatoria';
  if (!VERSION_PATTERN.test(v)) return 'Usa LATEST, SNAPSHOT o una versione come 1.21.4';
  return null;
}

export function resourcesError(cpu: number, memory: number): string | null {
  if (!Number.isInteger(cpu) || cpu < CPU_MIN || cpu > CPU_MAX) {
    return `CPU tra ${CPU_MIN}m e ${CPU_MAX}m`;
  }
  if (!Number.isInteger(memory) || memory < MEMORY_MIN || memory > MEMORY_MAX) {
    return `Memoria tra ${MEMORY_MIN} MB e ${MEMORY_MAX} MB`;
  }
  return null;
}

export const CURSEFORGE_PAGE = /^https:\/\/(www\.)?curseforge\.com\/minecraft\/modpacks\/[A-Za-z0-9_-]+\/?$/;

/** Error of the type-specific step, null when the step is complete. */
export function sourceError(
  type: ServerInstanceDto.TypeEnum,
  input: { version: string; modrinthProjectId?: string | null; curseforgePageUrl?: string | null },
): string | null {
  switch (type) {
    case ServerInstanceDto.TypeEnum.Modrinth:
      return input.modrinthProjectId ? null : 'Seleziona un modpack Modrinth';
    case ServerInstanceDto.TypeEnum.Curseforge:
      if (!input.curseforgePageUrl) return 'Seleziona un modpack CurseForge';
      return CURSEFORGE_PAGE.test(input.curseforgePageUrl)
        ? null
        : 'Questo modpack non ha una pagina CurseForge valida';
    default:
      return versionError(input.version);
  }
}
