import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpContext, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { finalize } from 'rxjs';
import { INTEGRATIONSService } from '../../../core/api-client/api/integrations.service';
import { SERVERService } from '../../../core/api-client/api/server.service';
import { Modpack } from '../../../core/api-client/model/modpack';
import { ServerInstanceDto } from '../../../core/api-client/model/server-instance-dto';
import { formatCompact, formatCpu, formatMemory } from '../../../core/format';
import { SKIP_ERROR_NOTIFICATION, errorMessage } from '../../../core/interceptors/error-interceptor';
import { BackdropClose } from '../../../shared/backdrop-close';
import { resourcesError, serverNameError, sourceError } from '../server-validation';

interface ModrinthHit {
  project_id: string;
  title: string;
  description: string;
  icon_url?: string;
  downloads: number;
}

type TypeEnum = ServerInstanceDto.TypeEnum;
const Types = ServerInstanceDto.TypeEnum;

export interface ServerTypeOption {
  value: TypeEnum;
  label: string;
  icon: string;
  description: string;
}

export const SERVER_TYPES: readonly ServerTypeOption[] = [
  { value: Types.Vanilla, label: 'Vanilla', icon: 'grass', description: 'Il gioco base, senza modifiche' },
  { value: Types.Plugin, label: 'Plugin', icon: 'extension', description: 'Paper/Spigot con plugin' },
  { value: Types.Mod, label: 'Mod', icon: 'build', description: 'Forge/Fabric con mod' },
  { value: Types.Modrinth, label: 'Modrinth', icon: 'hub', description: 'Installa un modpack da Modrinth' },
  { value: Types.Curseforge, label: 'CurseForge', icon: 'bolt', description: 'Installa un modpack da CurseForge' },
];

const STEPS = ['Base', 'Risorse', 'Sorgente', 'Riepilogo'] as const;
const MODRINTH_SEARCH = 'https://api.modrinth.com/v2/search';

/** Wizard in a modal: name and type, resources, game version or modpack, summary. */
@Component({
  selector: 'app-create-server-dialog',
  standalone: true,
  imports: [FormsModule, MatIconModule, BackdropClose],
  templateUrl: './create-server-dialog.html',
  styleUrl: './create-server-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'close()' },
})
export class CreateServerDialog {
  private serverApi = inject(SERVERService);
  private integrations = inject(INTEGRATIONSService);
  private http = inject(HttpClient);
  private destroyRef = inject(DestroyRef);

  readonly existingNames = input<readonly string[]>([]);
  readonly curseforgeEnabled = input(true);
  readonly closed = output<void>();
  readonly created = output<string>();

  readonly steps = STEPS;
  readonly types = SERVER_TYPES;
  readonly TypeEnum = Types;
  readonly CPU = { min: 200, max: 4000, step: 200 };
  readonly MEMORY = { min: 512, max: 16384, step: 256 };

  readonly step = signal(1);

  // step 1
  readonly name = signal('');
  readonly type = signal<TypeEnum>(Types.Vanilla);
  readonly eula = signal(false);
  // step 2
  readonly cpu = signal(400);
  readonly memory = signal(1024);
  // step 3
  readonly version = signal('');
  readonly modrinthQuery = signal('');
  readonly modrinthResults = signal<ModrinthHit[]>([]);
  readonly modrinthLoading = signal(false);
  readonly modrinthSelected = signal<ModrinthHit | null>(null);
  readonly modrinthError = signal<string | null>(null);
  readonly curseforgeQuery = signal('');
  readonly curseforgeResults = signal<Modpack[]>([]);
  readonly curseforgeLoading = signal(false);
  readonly curseforgeSelected = signal<Modpack | null>(null);
  readonly curseforgeError = signal<string | null>(null);

  readonly creating = signal(false);
  /** Error of the last create request, shown next to the data that caused it (not as a toast). */
  readonly createError = signal<string | null>(null);

  readonly nameTouched = signal(false);
  readonly nameError = computed(() => serverNameError(this.name(), [...this.existingNames()]));
  readonly resourceError = computed(() => resourcesError(this.cpu(), this.memory()));
  readonly stepThreeError = computed(() =>
    sourceError(this.type(), {
      version: this.version(),
      modrinthProjectId: this.modrinthSelected()?.project_id,
      curseforgePageUrl: this.curseforgeSelected()?.websiteUrl,
    }),
  );
  readonly typeOption = computed(() => this.types.find((t) => t.value === this.type()) ?? this.types[0]);
  readonly typeAvailable = computed(() => this.type() !== Types.Curseforge || this.curseforgeEnabled());
  readonly canProceedStep1 = computed(() => !this.nameError() && this.eula() && this.typeAvailable());
  readonly canProceedStep3 = computed(() => !this.stepThreeError());
  readonly canProceed = computed(() => {
    switch (this.step()) {
      case 1:
        return this.canProceedStep1();
      case 2:
        return !this.resourceError();
      case 3:
        return this.canProceedStep3();
      default:
        return true;
    }
  });

  readonly sourceSummary = computed(() => {
    switch (this.type()) {
      case Types.Modrinth:
        return this.modrinthSelected()?.title ?? '—';
      case Types.Curseforge:
        return this.curseforgeSelected()?.name ?? '—';
      default:
        return this.version().trim() || '—';
    }
  });
  readonly sourceIcon = computed(() => {
    switch (this.type()) {
      case Types.Modrinth:
        return this.modrinthSelected()?.icon_url ?? '';
      case Types.Curseforge:
        return this.curseforgeSelected()?.logoUrl ?? '';
      default:
        return '';
    }
  });

  close() {
    if (!this.creating()) this.closed.emit();
  }

  next() {
    if (!this.canProceed() || this.step() >= STEPS.length) return;
    this.createError.set(null);
    this.step.update((s) => s + 1);
  }

  back() {
    if (this.step() > 1) this.step.update((s) => s - 1);
  }

  selectType(type: TypeEnum) {
    this.type.set(type);
    this.createError.set(null);
  }

  /** Slider position (0–100) used to paint the filled part of the track. */
  fill(value: number, range: { min: number; max: number }): string {
    return `${((value - range.min) / (range.max - range.min)) * 100}%`;
  }

  readonly formatMemory = formatMemory;
  readonly formatCpu = formatCpu;
  readonly compact = formatCompact;

  create() {
    if (this.creating() || !this.canProceedStep1() || this.resourceError() || !this.canProceedStep3()) return;
    this.creating.set(true);
    this.createError.set(null);

    const type = this.type();
    const dto: ServerInstanceDto = {
      name: this.name().trim(),
      type,
      cpu: this.cpu(),
      memory: this.memory(),
      eula: this.eula(),
    };
    if (type === Types.Modrinth) {
      dto.modrinthProjectId = this.modrinthSelected()?.project_id;
    } else if (type === Types.Curseforge) {
      dto.curseforgePageUrl = this.curseforgeSelected()?.websiteUrl ?? '';
    } else {
      dto.version = this.version().trim();
    }

    const context = new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);
    this.serverApi
      .createServer(dto, 'body', false, { context })
      .pipe(
        finalize(() => this.creating.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.created.emit(dto.name ?? ''),
        error: (err: HttpErrorResponse) => this.createError.set(errorMessage(err)),
      });
  }

  searchModrinth() {
    const query = this.modrinthQuery().trim();
    if (!query) return;
    this.modrinthLoading.set(true);
    this.modrinthSelected.set(null);
    this.modrinthError.set(null);

    const params = new HttpParams().set('query', query).set('facets', '[["project_type:modpack"]]').set('limit', 12);
    this.http
      .get<{ hits: ModrinthHit[] }>(MODRINTH_SEARCH, { params })
      .pipe(
        finalize(() => this.modrinthLoading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (res) => this.modrinthResults.set(res.hits),
        error: () => this.modrinthError.set('Ricerca su Modrinth non riuscita, riprova'),
      });
  }

  searchCurseforge() {
    const query = this.curseforgeQuery().trim();
    if (!query) return;
    this.curseforgeLoading.set(true);
    this.curseforgeSelected.set(null);
    this.curseforgeError.set(null);

    // through the backend: the API key never reaches the browser
    const context = new HttpContext().set(SKIP_ERROR_NOTIFICATION, true);
    this.integrations
      .searchCurseForge(query, 'body', false, { context })
      .pipe(
        finalize(() => this.curseforgeLoading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (hits) => this.curseforgeResults.set(hits),
        error: (err: HttpErrorResponse) => this.curseforgeError.set(errorMessage(err)),
      });
  }
}
