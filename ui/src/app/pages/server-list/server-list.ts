import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { SERVERService } from '../../core/api-client/api/server.service';
import { ServerInstanceDto } from '../../core/api-client/model/server-instance-dto';
import { CURSEFORGE_API_KEY } from '../../app.config';

interface ModrinthHit {
  project_id: string;
  title: string;
  description: string;
  icon_url?: string;
  downloads: number;
  versions: string[];
}

interface CurseForgeHit {
  id: number;
  name: string;
  summary: string;
  logo?: { url: string };
  downloadCount: number;
  links?: { websiteUrl: string };
}

@Component({
  selector: 'app-server-list',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatButtonModule, MatTooltipModule],
  templateUrl: './server-list.html',
  styleUrl: './server-list.scss',
})
export class ServerList implements OnInit {
  private serverService = inject(SERVERService);
  private router = inject(Router);
  private http = inject(HttpClient);
  private curseforgeKey = inject(CURSEFORGE_API_KEY);

  servers = signal<ServerInstanceDto[]>([]);
  loading = signal(false);

  // Wizard
  wizardOpen = signal(false);
  wizardStep = signal(1);

  // Step 1
  newName = signal('');
  newType = signal<ServerInstanceDto.TypeEnum>(ServerInstanceDto.TypeEnum.Vanilla);
  newEula = signal(false);

  // Step 2
  newCpu = signal(400);
  newMemory = signal(1024);

  // Step 3 — versione manuale
  newVersion = signal('');

  // Step 3 — Modrinth
  modrinthQuery = signal('');
  modrinthResults = signal<ModrinthHit[]>([]);
  modrinthLoading = signal(false);
  modrinthSelected = signal<ModrinthHit | null>(null);

  // Step 3 — CurseForge
  curseforgeQuery = signal('');
  curseforgeResults = signal<CurseForgeHit[]>([]);
  curseforgeLoading = signal(false);
  curseforgeSelected = signal<CurseForgeHit | null>(null);

  // Delete
  deleteTarget = signal<ServerInstanceDto | null>(null);

  // Creazione
  creating = signal(false);

  readonly TypeEnum = ServerInstanceDto.TypeEnum;
  readonly CPU_VALUES = Array.from({ length: 10 }, (_, i) => 200 + i * 200);
  readonly MEMORY_VALUES = Array.from({ length: 30 }, (_, i) => 512 + i * 256);

  ngOnInit() {
    this.loadServers();
  }

  // ─── Lista ─────────────────────────────────────────────────────

  loadServers() {
    this.loading.set(true);
    this.serverService.getAllServers().subscribe({
      next: (list) => { this.servers.set(list); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  openServer(name: string) {
    this.router.navigate(['/servers', name]);
  }

  openFiles(name: string, event: Event) {
    event.stopPropagation();
    this.router.navigate(['/servers', name, 'files']);
  }

  // ─── Delete ────────────────────────────────────────────────────

  confirmDelete() {
    const target = this.deleteTarget();
    if (!target?.name) return;
    this.serverService.deleteServer(target.name).subscribe({
      next: () => { this.deleteTarget.set(null); this.loadServers(); }
    });
  }

  // ─── Wizard ────────────────────────────────────────────────────

  openWizard() {
    this.wizardStep.set(1);
    this.newName.set('');
    this.newType.set(ServerInstanceDto.TypeEnum.Vanilla);
    this.newEula.set(false);
    this.newCpu.set(400);
    this.newMemory.set(1024);
    this.newVersion.set('');
    this.modrinthQuery.set('');
    this.modrinthResults.set([]);
    this.modrinthSelected.set(null);
    this.curseforgeQuery.set('');
    this.curseforgeResults.set([]);
    this.curseforgeSelected.set(null);
    this.wizardOpen.set(true);
  }

  closeWizard() {
    if (this.creating()) return;
    this.wizardOpen.set(false);
  }

  nextStep() {
    if (this.wizardStep() === 1 && !this.canProceedStep1()) return;
    if (this.wizardStep() === 3 && !this.canProceedStep3()) return;
    this.wizardStep.update(s => s + 1);
  }

  prevStep() { this.wizardStep.update(s => s - 1); }

  canProceedStep1(): boolean {
    return this.newName().trim().length > 0 && this.newEula();
  }

  canProceedStep3(): boolean {
    const type = this.newType();
    if (type === this.TypeEnum.Vanilla || type === this.TypeEnum.Plugin || type === this.TypeEnum.Mod) {
      return this.newVersion().trim().length > 0;
    }
    if (type === this.TypeEnum.Modrinth) return !!this.modrinthSelected();
    if (type === this.TypeEnum.Curseforge) return !!this.curseforgeSelected();
    return false;
  }

  // Riepilogo per step 4
  getRecapVersion(): string {
    const type = this.newType();
    if (type === this.TypeEnum.Vanilla || type === this.TypeEnum.Plugin || type === this.TypeEnum.Mod) {
      return this.newVersion().trim();
    }
    if (type === this.TypeEnum.Modrinth && this.modrinthSelected()) {
      return this.modrinthSelected()!.title;
    }
    if (type === this.TypeEnum.Curseforge && this.curseforgeSelected()) {
      return this.curseforgeSelected()!.name;
    }
    return '—';
  }

  getRecapIcon(): string {
    const type = this.newType();
    if (type === this.TypeEnum.Modrinth && this.modrinthSelected()?.icon_url) {
      return this.modrinthSelected()!.icon_url!;
    }
    if (type === this.TypeEnum.Curseforge && this.curseforgeSelected()?.logo?.url) {
      return this.curseforgeSelected()!.logo!.url;
    }
    return '';
  }

  create() {
    if (!this.canProceedStep3()) return;
    this.creating.set(true);

    const type = this.newType();
    const dto: ServerInstanceDto = {
      name: this.newName().trim(),
      type,
      cpu: this.newCpu(),
      memory: this.newMemory(),
      eula: this.newEula(),
    };

    if (type === this.TypeEnum.Vanilla || type === this.TypeEnum.Plugin || type === this.TypeEnum.Mod) {
      dto.version = this.newVersion().trim();
    } else if (type === this.TypeEnum.Modrinth && this.modrinthSelected()) {
      dto.modrinthProjectId = this.modrinthSelected()!.project_id;
    } else if (type === this.TypeEnum.Curseforge && this.curseforgeSelected()) {
      dto.curseforgePageUrl = this.curseforgeSelected()!.links?.websiteUrl ?? '';
    }

    this.serverService.createServer(dto).subscribe({
      next: () => {
        this.creating.set(false);
        this.closeWizard();
        this.loadServers();
      },
      error: () => this.creating.set(false)
    });
  }

  // ─── Modrinth ──────────────────────────────────────────────────

  searchModrinth() {
    const q = this.modrinthQuery().trim();
    if (!q) return;
    this.modrinthLoading.set(true);
    this.modrinthSelected.set(null);

    this.http.get<{ hits: ModrinthHit[] }>(
      `https://api.modrinth.com/v2/search?query=${encodeURIComponent(q)}&facets=[["project_type:modpack"]]&limit=12`
    ).subscribe({
      next: (res) => { this.modrinthResults.set(res.hits); this.modrinthLoading.set(false); },
      error: () => this.modrinthLoading.set(false)
    });
  }

  // ─── CurseForge ────────────────────────────────────────────────

  searchCurseforge() {
    const q = this.curseforgeQuery().trim();
    if (!q) return;
    this.curseforgeLoading.set(true);
    this.curseforgeSelected.set(null);

    this.http.get<{ data: CurseForgeHit[] }>(
      `https://api.curseforge.com/v1/mods/search?gameId=432&classId=4471&searchFilter=${encodeURIComponent(q)}&pageSize=12`,
      { headers: new HttpHeaders({ 'x-api-key': this.curseforgeKey }) }
    ).subscribe({
      next: (res) => { this.curseforgeResults.set(res.data); this.curseforgeLoading.set(false); },
      error: () => this.curseforgeLoading.set(false)
    });
  }

  // ─── Helpers ───────────────────────────────────────────────────

  getTypeLabel(type?: ServerInstanceDto.TypeEnum): string {
    switch (type) {
      case this.TypeEnum.Vanilla:    return 'Vanilla';
      case this.TypeEnum.Plugin:     return 'Plugin';
      case this.TypeEnum.Mod:        return 'Mod';
      case this.TypeEnum.Modrinth:   return 'Modrinth';
      case this.TypeEnum.Curseforge: return 'CurseForge';
      default: return '—';
    }
  }

  getTypeIcon(type?: ServerInstanceDto.TypeEnum): string {
    switch (type) {
      case this.TypeEnum.Vanilla:    return 'grass';
      case this.TypeEnum.Plugin:     return 'extension';
      case this.TypeEnum.Mod:        return 'build';
      case this.TypeEnum.Modrinth:   return 'hub';
      case this.TypeEnum.Curseforge: return 'bolt';
      default: return 'dns';
    }
  }

  formatMemory(mb: number): string {
    return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb} MB`;
  }
}