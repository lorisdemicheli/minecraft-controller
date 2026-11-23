import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

const WIDTH = 240;
const HEIGHT = 56;

/** Minimal area chart of a series of samples; `max` fixes the top of the scale (e.g. the limit). */
@Component({
  selector: 'app-sparkline',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg [attr.viewBox]="'0 0 ' + width + ' ' + height" preserveAspectRatio="none" role="img" [attr.aria-label]="label()">
      @if (line(); as d) {
        <path class="area" [attr.d]="area()" />
        <path class="line" [attr.d]="d" />
      } @else {
        <line class="idle" x1="0" [attr.y1]="height - 1" [attr.x2]="width" [attr.y2]="height - 1" />
      }
    </svg>
  `,
  styles: `
    :host { display: block; color: var(--accent); }
    svg { display: block; width: 100%; height: 56px; overflow: visible; }
    .line { fill: none; stroke: currentColor; stroke-width: 2; vector-effect: non-scaling-stroke; stroke-linejoin: round; stroke-linecap: round; }
    .area { fill: currentColor; opacity: 0.14; stroke: none; }
    .idle { stroke: var(--border-strong); stroke-width: 2; stroke-dasharray: 4 6; vector-effect: non-scaling-stroke; }
  `,
})
export class Sparkline {
  readonly values = input<readonly number[]>([]);
  readonly max = input<number | null>(null);
  readonly label = input('Andamento');

  readonly width = WIDTH;
  readonly height = HEIGHT;

  private points = computed(() => {
    const values = this.values();
    if (values.length < 2) return [];
    const top = this.max() ?? Math.max(...values, 1);
    const step = WIDTH / (values.length - 1);
    return values.map((v, i) => [i * step, HEIGHT - 2 - (Math.min(v, top) / top) * (HEIGHT - 4)] as const);
  });

  readonly line = computed(() => {
    const pts = this.points();
    return pts.length ? 'M' + pts.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' L') : '';
  });

  readonly area = computed(() => `${this.line()} L${WIDTH},${HEIGHT} L0,${HEIGHT} Z`);
}
