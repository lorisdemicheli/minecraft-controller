import { Injectable, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { MatSnackBar } from '@angular/material/snack-bar';

/** Short messages for the user (Material snack bar). Repeated identical errors are collapsed. */
@Injectable({ providedIn: 'root' })
export class Notifications {
  private snackBar = inject(MatSnackBar);
  private platformId = inject(PLATFORM_ID);
  private last = { text: '', at: 0 };

  error(message: string) {
    this.show(message, 'notification--error', 6000);
  }

  info(message: string) {
    this.show(message, 'notification--info', 3000);
  }

  private show(message: string, panelClass: string, duration: number) {
    if (!isPlatformBrowser(this.platformId)) return;
    const now = Date.now();
    if (message === this.last.text && now - this.last.at < 3000) return;
    this.last = { text: message, at: now };
    this.snackBar.open(message, 'OK', { duration, panelClass });
  }
}
