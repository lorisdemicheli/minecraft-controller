import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** The app logo: four blocks, one of them lit. Pure CSS, so it follows the theme. */
@Component({
  selector: 'app-brand-mark',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="blocks" aria-hidden="true"><i></i><i></i><i></i><i></i></span>`,
  host: { '[style.--size.px]': 'size()' },
  styles: `
    :host { display: inline-flex; width: var(--size, 36px); height: var(--size, 36px); flex: none; }
    .blocks {
      display: grid; grid-template: 1fr 1fr / 1fr 1fr; gap: 12%;
      width: 100%; height: 100%; padding: 14%;
      background: var(--surface-3); border: 1px solid var(--border-strong); border-radius: 28%;
    }
    i { border-radius: 22%; background: var(--text-3); opacity: 0.55; }
    i:nth-child(1) { background: var(--accent); opacity: 1; box-shadow: 0 0 14px var(--accent-soft); }
    i:nth-child(4) { opacity: 0.9; background: var(--text); }
  `,
})
export class BrandMark {
  readonly size = input(36);
}
