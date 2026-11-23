import { Directive, output } from '@angular/core';

/**
 * Closes a modal when its backdrop (and nothing inside it) is pressed.
 *
 * It is a directive, not an inline `(mousedown)="$event.target === $event.currentTarget && close()"`:
 * Angular calls `preventDefault()` when a listener expression evaluates to `false`, and a prevented
 * `mousedown` stops every field inside the modal from taking the focus.
 */
@Directive({
  selector: '[appBackdropClose]',
  standalone: true,
  host: { '(mousedown)': 'onMouseDown($event)' },
})
export class BackdropClose {
  readonly appBackdropClose = output<void>();

  protected onMouseDown(event: MouseEvent) {
    if (event.target === event.currentTarget) this.appBackdropClose.emit();
  }
}
