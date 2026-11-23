import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, PLATFORM_ID, computed, inject, signal } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { filter } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Auth } from '../../core/auth/auth';
import { ServersStore } from '../../core/servers/servers-store';
import { Theme } from '../../core/theme/theme';
import { BrandMark } from '../../shared/brand-mark';

const REFRESH_MS = 15_000;

/** App shell: sidebar with the servers (state at a glance), theme switch, logout. */
@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatIconModule, BrandMark],
  templateUrl: './main-layout.html',
  styleUrl: './main-layout.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MainLayout implements OnInit {
  private auth = inject(Auth);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);
  private browser = isPlatformBrowser(inject(PLATFORM_ID));

  readonly store = inject(ServersStore);
  readonly theme = inject(Theme);

  readonly menuOpen = signal(false);
  readonly query = signal('');
  readonly filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    return q ? this.store.servers().filter((s) => (s.name ?? '').toLowerCase().includes(q)) : this.store.servers();
  });

  ngOnInit() {
    this.router.events
      .pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.menuOpen.set(false));

    this.store.refresh();
    if (this.browser) {
      const timer = setInterval(() => this.store.refresh(), REFRESH_MS);
      this.destroyRef.onDestroy(() => clearInterval(timer));
    }
  }

  onSearch(event: Event) {
    this.query.set((event.target as HTMLInputElement).value);
  }

  newServer() {
    this.router.navigate(['/servers'], { queryParams: { new: 1 } });
  }

  logout() {
    this.auth.logout();
  }
}
