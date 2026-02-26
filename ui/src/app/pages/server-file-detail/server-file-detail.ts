import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FILESYSTEMService } from '../../core/api-client/api/filesystem.service';
import { FileEntry } from '../../core/api-client/model/file-entry';

type ModalMode = 'none' | 'new-file' | 'new-dir' | 'rename' | 'delete';

@Component({
  selector: 'app-server-files',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatButtonModule, MatTooltipModule],
  templateUrl: './server-file-detail.html',
  styleUrl: './server-file-detail.scss',
})
export class ServerFileDetail {
  private route = inject(ActivatedRoute);
  private fs = inject(FILESYSTEMService);

  serverName = this.route.snapshot.paramMap.get('id') ?? '';

  currentPath = signal('/');
  breadcrumbs = signal<{ label: string; path: string }[]>([{ label: 'Home', path: '/' }]);

  entries = signal<FileEntry[]>([]);
  loading = signal(false);

  folders = computed(() => this.entries().filter(e => e.type === FileEntry.TypeEnum.Directory));
  files = computed(() => this.entries().filter(e => e.type !== FileEntry.TypeEnum.Directory));

  // Editor
  editorOpen = signal(false);
  editorPath = signal('');
  editorContent = signal('');
  editorSaving = signal(false);
  editorLoading = signal(false);
  editorSaved = signal(false);

  // Modal
  modalMode = signal<ModalMode>('none');
  modalTarget = signal<FileEntry | null>(null);
  inputValue = signal('');

  uploadLoading = signal(false);

  readonly TypeEnum = FileEntry.TypeEnum;

  ngOnInit() {
    this.loadDir('/');
  }

  // ─── Navigazione ───────────────────────────────────────────────

  loadDir(path: string) {
    this.loading.set(true);
    this.currentPath.set(path);
    this.updateBreadcrumbs(path);

    this.fs.listFiles(this.serverName, path).subscribe({
      next: (entries) => {
        const sorted = [...entries].sort((a, b) => {
          if (a.type === FileEntry.TypeEnum.Directory && b.type !== FileEntry.TypeEnum.Directory) return -1;
          if (a.type !== FileEntry.TypeEnum.Directory && b.type === FileEntry.TypeEnum.Directory) return 1;
          return (a.name ?? '').localeCompare(b.name ?? '');
        });
        this.entries.set(sorted);
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
  }

  navigateTo(path: string) {
    this.closeEditor();
    this.loadDir(path);
  }

  openEntry(entry: FileEntry) {
    if (entry.type === FileEntry.TypeEnum.Directory) {
      this.navigateTo(this.joinPath(this.currentPath(), entry.name!));
    } else if (this.isTextFile(entry.name!)) {
      this.openEditor(entry);
    } else {
      this.download(entry);
    }
  }

  private updateBreadcrumbs(path: string) {
    if (path === '/') {
      this.breadcrumbs.set([{ label: 'Home', path: '/' }]);
      return;
    }
    const parts = path.split('/').filter(Boolean);
    const crumbs = [{ label: 'Home', path: '/' }];
    let cumulative = '';
    for (const part of parts) {
      cumulative += '/' + part;
      crumbs.push({ label: part, path: cumulative });
    }
    this.breadcrumbs.set(crumbs);
  }

  // ─── Editor ────────────────────────────────────────────────────

  openEditor(entry: FileEntry) {
    const fullPath = this.joinPath(this.currentPath(), entry.name!);
    this.editorPath.set(fullPath);
    this.editorContent.set('');
    this.editorOpen.set(true);
    this.editorLoading.set(true);
    this.editorSaved.set(false);

    this.fs.getContent(this.serverName, fullPath).subscribe({
      next: (content) => {
        this.editorContent.set(content ?? '');
        this.editorLoading.set(false);
      },
      error: () => {
        this.editorContent.set('');
        this.editorLoading.set(false);
      }
    });
  }

  saveEditor() {
    this.editorSaving.set(true);
    this.editorSaved.set(false);
    this.fs.setContent(this.serverName, this.editorPath(), this.editorContent()).subscribe({
      next: () => {
        this.editorSaving.set(false);
        this.editorSaved.set(true);
        // Aggiorna la lista per riflettere la nuova dimensione del file
        this.loadDir(this.currentPath());
        setTimeout(() => this.editorSaved.set(false), 3000);
      },
      error: () => this.editorSaving.set(false)
    });
  }

  closeEditor() {
    this.editorOpen.set(false);
    this.editorPath.set('');
    this.editorContent.set('');
    this.editorSaved.set(false);
  }

  // ─── Download ──────────────────────────────────────────────────

  download(entry: FileEntry) {
    const fullPath = this.joinPath(this.currentPath(), entry.name!);
    this.fs.downloadFile(this.serverName, fullPath, 'body', false, {
      httpHeaderAccept: 'application/octet-stream'
    }).subscribe({
      next: (blob: Blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = entry.name!;
        a.click();
        URL.revokeObjectURL(url);
      }
    });
  }

  // ─── Upload ────────────────────────────────────────────────────

  onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) return;

    this.uploadLoading.set(true);
    const file = input.files[0];
    const destPath = this.joinPath(this.currentPath(), file.name);

    this.fs.uploadFile(this.serverName, destPath, file).subscribe({
      next: () => {
        this.uploadLoading.set(false);
        this.loadDir(this.currentPath()); // ← refresh automatico
      },
      error: () => this.uploadLoading.set(false)
    });
    input.value = '';
  }

  // ─── Modal ─────────────────────────────────────────────────────

  openModal(mode: ModalMode, entry?: FileEntry) {
    this.modalMode.set(mode);
    this.modalTarget.set(entry ?? null);
    this.inputValue.set(mode === 'rename' ? (entry?.name ?? '') : '');
  }

  closeModal() {
    this.modalMode.set('none');
    this.modalTarget.set(null);
    this.inputValue.set('');
  }

  confirmModal() {
    const mode = this.modalMode();
    const path = this.currentPath();
    const name = this.inputValue().trim();

    if (mode === 'new-file') {
      if (!name) return;
      this.fs.createEmptyFile(this.serverName, this.joinPath(path, name)).subscribe({
        next: () => {
          this.closeModal();
          this.loadDir(path); // ← refresh
        }
      });

    } else if (mode === 'new-dir') {
      if (!name) return;
      this.fs.createDirectory(this.serverName, this.joinPath(path, name)).subscribe({
        next: () => {
          this.closeModal();
          this.loadDir(path); // ← refresh
        }
      });

    } else if (mode === 'rename') {
      if (!name || !this.modalTarget()) return;
      const oldPath = this.joinPath(path, this.modalTarget()!.name!);
      this.fs.rename(this.serverName, oldPath, name).subscribe({
        next: () => {
          this.closeModal();
          this.loadDir(path); // ← refresh
          // Se il file rinominato era aperto nell'editor, chiudilo
          if (this.editorOpen() && this.editorPath() === oldPath) {
            this.closeEditor();
          }
        }
      });

    } else if (mode === 'delete') {
      if (!this.modalTarget()) return;
      const delPath = this.joinPath(path, this.modalTarget()!.name!);
      this.fs.deletePath(this.serverName, delPath).subscribe({
        next: () => {
          this.closeModal();
          this.loadDir(path); // ← refresh
          // Se il file eliminato era aperto nell'editor, chiudilo
          if (this.editorOpen() && this.editorPath() === delPath) {
            this.closeEditor();
          }
        }
      });
    }
  }

  // ─── Helpers ───────────────────────────────────────────────────

  joinPath(parent: string, name: string): string {
    return parent === '/' ? `/${name}` : `${parent}/${name}`;
  }

  formatSize(bytes?: number): string {
    if (bytes == null) return '—';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  formatDate(date?: string): string {
    if (!date) return '—';
    return new Date(date).toLocaleDateString('it-IT', { day: '2-digit', month: 'short', year: 'numeric' });
  }

  getFileIcon(entry: FileEntry): string {
    if (entry.type === FileEntry.TypeEnum.Directory) return 'folder';
    const n = entry.name ?? '';
    if (/\.(yml|yaml)$/i.test(n)) return 'settings';
    if (/\.json$/i.test(n)) return 'data_object';
    if (/\.properties$/i.test(n)) return 'tune';
    if (/\.(txt|log|md)$/i.test(n)) return 'description';
    if (/\.jar$/i.test(n)) return 'inventory_2';
    if (/\.(sh|bat)$/i.test(n)) return 'terminal';
    if (/\.(png|jpg|jpeg|gif|webp)$/i.test(n)) return 'image';
    if (/\.zip$/i.test(n)) return 'folder_zip';
    return 'insert_drive_file';
  }

  isTextFile(name: string): boolean {
    return /\.(yml|yaml|json|properties|txt|log|cfg|conf|toml|ini|sh|bat|md|xml)$/i.test(name);
  }
}