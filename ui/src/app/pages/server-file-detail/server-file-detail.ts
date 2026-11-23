import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { concat, finalize, last } from 'rxjs';
import { FILESYSTEMService } from '../../core/api-client/api/filesystem.service';
import { FileEntry } from '../../core/api-client/model/file-entry';
import { formatBytes } from '../../core/format';
import { BackdropClose } from '../../shared/backdrop-close';

type ModalMode = 'none' | 'new-file' | 'new-dir' | 'rename' | 'delete' | 'discard';

const TEXT_FILE = /\.(yml|yaml|json|properties|txt|log|cfg|conf|toml|ini|sh|bat|md|xml|csv|mcmeta)$/i;

const ICONS: readonly (readonly [RegExp, string])[] = [
  [/\.(yml|yaml)$/i, 'settings'],
  [/\.json$/i, 'data_object'],
  [/\.(properties|cfg|conf|toml|ini)$/i, 'tune'],
  [/\.(txt|log|md)$/i, 'description'],
  [/\.jar$/i, 'inventory_2'],
  [/\.(sh|bat)$/i, 'terminal'],
  [/\.(png|jpg|jpeg|gif|webp)$/i, 'image'],
  [/\.(zip|gz|tar|7z)$/i, 'folder_zip'],
];

/** File manager of one server's folder: browse, upload, edit text files, rename, delete. */
@Component({
  selector: 'app-server-files',
  standalone: true,
  imports: [FormsModule, RouterLink, MatIconModule, BackdropClose],
  templateUrl: './server-file-detail.html',
  styleUrl: './server-file-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServerFileDetail implements OnInit {
  private route = inject(ActivatedRoute);
  private fs = inject(FILESYSTEMService);
  private destroyRef = inject(DestroyRef);

  readonly serverName = this.route.snapshot.paramMap.get('id') ?? '';
  readonly TypeEnum = FileEntry.TypeEnum;
  readonly formatBytes = formatBytes;

  readonly currentPath = signal('/');
  readonly entries = signal<FileEntry[]>([]);
  readonly loading = signal(false);
  readonly loadFailed = signal(false);

  readonly breadcrumbs = computed(() => {
    const crumbs = [{ label: this.serverName, path: '/' }];
    let path = '';
    for (const part of this.currentPath().split('/').filter(Boolean)) {
      path += '/' + part;
      crumbs.push({ label: part, path });
    }
    return crumbs;
  });
  readonly folders = computed(() => this.entries().filter((e) => e.type === FileEntry.TypeEnum.Directory));
  readonly files = computed(() => this.entries().filter((e) => e.type !== FileEntry.TypeEnum.Directory));
  readonly isRoot = computed(() => this.currentPath() === '/');

  // editor
  readonly editorOpen = signal(false);
  readonly editorPath = signal('');
  readonly editorContent = signal('');
  readonly savedContent = signal('');
  readonly editorLoading = signal(false);
  readonly editorSaving = signal(false);
  readonly editorSaved = signal(false);
  readonly dirty = computed(() => this.editorOpen() && this.editorContent() !== this.savedContent());
  readonly editorName = computed(() => this.editorPath().split('/').pop() ?? '');

  // modal
  readonly modalMode = signal<ModalMode>('none');
  readonly modalTarget = signal<FileEntry | null>(null);
  readonly inputValue = signal('');
  readonly modalBusy = signal(false);
  readonly inputError = computed(() => {
    const name = this.inputValue().trim();
    if (!name) return null;
    if (name === '.' || name === '..' || name.includes('/')) return 'Nome non valido';
    const taken = this.entries().some((e) => e.name === name && e.name !== this.modalTarget()?.name);
    return taken ? 'Esiste già un elemento con questo nome' : null;
  });
  readonly canConfirm = computed(() => {
    if (this.modalBusy()) return false;
    const mode = this.modalMode();
    if (mode === 'delete' || mode === 'discard') return true;
    return !!this.inputValue().trim() && !this.inputError();
  });

  // upload
  readonly uploading = signal(false);
  readonly uploadDone = signal(0);
  readonly uploadTotal = signal(0);
  readonly dragging = signal(false);

  /** What to do once the user agrees to discard the unsaved changes. */
  private pendingPath: string | null = null;
  private pendingEntry: FileEntry | null = null;

  ngOnInit() {
    this.loadDir('/');
  }

  // ─── Navigazione ───────────────────────────────────────────────

  loadDir(path: string) {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.currentPath.set(path);

    this.fs
      .listFiles(this.serverName, path)
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (entries) => this.entries.set(this.sorted(entries)),
        error: () => {
          this.entries.set([]);
          this.loadFailed.set(true);
        },
      });
  }

  private sorted(entries: FileEntry[]): FileEntry[] {
    const isDir = (e: FileEntry) => e.type === FileEntry.TypeEnum.Directory;
    return [...entries].sort((a, b) => Number(isDir(b)) - Number(isDir(a)) || (a.name ?? '').localeCompare(b.name ?? ''));
  }

  navigateTo(path: string) {
    if (this.dirty()) {
      this.pendingPath = path;
      this.openModal('discard');
      return;
    }
    this.closeEditor(true);
    this.loadDir(path);
  }

  goUp() {
    const parent = this.currentPath().split('/').filter(Boolean).slice(0, -1);
    this.navigateTo(parent.length ? '/' + parent.join('/') : '/');
  }

  openEntry(entry: FileEntry) {
    const name = entry.name ?? '';
    if (entry.type === FileEntry.TypeEnum.Directory) {
      this.navigateTo(this.joinPath(this.currentPath(), name));
    } else if (this.isTextFile(name)) {
      this.openEditor(entry);
    } else {
      this.download(entry);
    }
  }

  // ─── Editor ────────────────────────────────────────────────────

  openEditor(entry: FileEntry) {
    const fullPath = this.joinPath(this.currentPath(), entry.name ?? '');
    if (this.dirty() && fullPath !== this.editorPath()) {
      this.openModal('discard');
      this.pendingEntry = entry;
      return;
    }
    this.editorPath.set(fullPath);
    this.editorContent.set('');
    this.savedContent.set('');
    this.editorOpen.set(true);
    this.editorLoading.set(true);
    this.editorSaved.set(false);

    this.fs
      .getContent(this.serverName, fullPath)
      .pipe(
        finalize(() => this.editorLoading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (content) => {
          this.editorContent.set(content ?? '');
          this.savedContent.set(content ?? '');
        },
        error: () => this.closeEditor(true),
      });
  }

  saveEditor() {
    if (!this.dirty() || this.editorSaving()) return;
    this.editorSaving.set(true);
    this.editorSaved.set(false);
    const content = this.editorContent();
    this.fs
      .setContent(this.serverName, this.editorPath(), content)
      .pipe(
        finalize(() => this.editorSaving.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.savedContent.set(content);
          this.editorSaved.set(true);
          this.loadDir(this.currentPath()); // the size changed
        },
      });
  }

  onEditorKeydown(event: KeyboardEvent) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault();
      this.saveEditor();
    }
  }

  /** Closes the editor; unsaved changes ask first unless `force`. */
  closeEditor(force = false) {
    if (!force && this.dirty()) {
      this.pendingPath = null;
      this.pendingEntry = null;
      this.openModal('discard');
      return;
    }
    this.editorOpen.set(false);
    this.editorPath.set('');
    this.editorContent.set('');
    this.savedContent.set('');
    this.editorSaved.set(false);
  }

  // ─── Download / upload ─────────────────────────────────────────

  download(entry: FileEntry) {
    const name = entry.name ?? 'file';
    this.fs
      .downloadFile(this.serverName, this.joinPath(this.currentPath(), name), 'body', false, {
        httpHeaderAccept: 'application/octet-stream',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob: Blob) => {
          const url = URL.createObjectURL(blob);
          const link = document.createElement('a');
          link.href = url;
          link.download = name;
          link.click();
          URL.revokeObjectURL(url);
        },
      });
  }

  onFilesSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    this.upload(Array.from(input.files ?? []));
    input.value = '';
  }

  onDragOver(event: DragEvent) {
    if (!event.dataTransfer?.types.includes('Files')) return;
    event.preventDefault();
    this.dragging.set(true);
  }

  onDrop(event: DragEvent) {
    event.preventDefault();
    this.dragging.set(false);
    this.upload(Array.from(event.dataTransfer?.files ?? []));
  }

  /** Uploads one file after the other into the current folder. */
  upload(files: File[]) {
    if (!files.length || this.uploading()) return;
    const folder = this.currentPath();
    this.uploading.set(true);
    this.uploadDone.set(0);
    this.uploadTotal.set(files.length);

    concat(
      ...files.map((file) =>
        this.fs.uploadFile(this.serverName, this.joinPath(folder, file.name), file).pipe(last()),
      ),
    )
      .pipe(
        finalize(() => {
          this.uploading.set(false);
          this.loadDir(this.currentPath());
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({ next: () => this.uploadDone.update((n) => n + 1) });
  }

  // ─── Modal ─────────────────────────────────────────────────────

  openModal(mode: ModalMode, entry?: FileEntry) {
    this.modalMode.set(mode);
    this.modalTarget.set(entry ?? null);
    this.inputValue.set(mode === 'rename' ? (entry?.name ?? '') : '');
  }

  /** Closes the modal unless a request is in flight. */
  dismissModal() {
    if (!this.modalBusy()) this.closeModal();
  }

  closeModal() {
    this.modalMode.set('none');
    this.modalTarget.set(null);
    this.inputValue.set('');
    this.pendingPath = null;
    this.pendingEntry = null;
  }

  confirmModal() {
    if (!this.canConfirm()) return;
    const mode = this.modalMode();
    const path = this.currentPath();
    const name = this.inputValue().trim();
    const target = this.modalTarget();

    if (mode === 'discard') {
      const nextPath = this.pendingPath;
      const nextEntry = this.pendingEntry;
      this.closeModal();
      this.closeEditor(true);
      if (nextPath) this.loadDir(nextPath);
      else if (nextEntry) this.openEditor(nextEntry);
      return;
    }

    const request =
      mode === 'new-file'
        ? this.fs.createEmptyFile(this.serverName, this.joinPath(path, name))
        : mode === 'new-dir'
          ? this.fs.createDirectory(this.serverName, this.joinPath(path, name))
          : mode === 'rename' && target
            ? this.fs.rename(this.serverName, this.joinPath(path, target.name ?? ''), name)
            : mode === 'delete' && target
              ? this.fs.deletePath(this.serverName, this.joinPath(path, target.name ?? ''))
              : null;
    if (!request) return;

    this.modalBusy.set(true);
    request
      .pipe(
        finalize(() => this.modalBusy.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          // the file open in the editor was renamed or deleted
          const affected = target && this.joinPath(path, target.name ?? '');
          if ((mode === 'rename' || mode === 'delete') && affected && (this.editorPath() === affected || this.editorPath().startsWith(affected + '/'))) {
            this.closeEditor(true);
          }
          this.closeModal();
          this.loadDir(path);
        },
      });
  }

  // ─── Helpers ───────────────────────────────────────────────────

  joinPath(parent: string, name: string): string {
    return parent === '/' ? `/${name}` : `${parent}/${name}`;
  }

  formatDate(date?: string): string {
    if (!date) return '—';
    return new Date(date).toLocaleString('it-IT', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  fileIcon(entry: FileEntry): string {
    if (entry.type === FileEntry.TypeEnum.Directory) return 'folder';
    const name = entry.name ?? '';
    return ICONS.find(([pattern]) => pattern.test(name))?.[1] ?? 'insert_drive_file';
  }

  isTextFile(name: string): boolean {
    return TEXT_FILE.test(name);
  }
}
