import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { BackdropClose } from './backdrop-close';

@Component({
  standalone: true,
  imports: [BackdropClose],
  template: `
    <div class="backdrop" (appBackdropClose)="closed = closed + 1">
      <input class="inside" />
    </div>
  `,
})
class Host {
  closed = 0;
}

describe('BackdropClose', () => {
  function setup() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return {
      host: fixture.componentInstance,
      backdrop: el.querySelector('.backdrop') as HTMLElement,
      inside: el.querySelector('.inside') as HTMLElement,
    };
  }

  const press = (target: HTMLElement) => {
    const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
    target.dispatchEvent(event);
    return event;
  };

  it('closes when the backdrop itself is pressed', () => {
    const { host, backdrop } = setup();
    press(backdrop);
    expect(host.closed).toBe(1);
  });

  it('does not close, and does not block the focus, when something inside is pressed', () => {
    const { host, inside } = setup();
    const event = press(inside);
    expect(host.closed).toBe(0);
    // a prevented mousedown would keep the input from taking the focus (the bug this guards against)
    expect(event.defaultPrevented).toBe(false);
  });
});
