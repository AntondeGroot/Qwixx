import { Component, computed, effect, ElementRef, inject, input, OnDestroy, signal } from '@angular/core';
import { NgClass, NgStyle } from '@angular/common';
import { DiceColor, DiceSvgService } from './dice-svg.service';

@Component({
  selector: 'app-dice',
  imports: [NgClass, NgStyle],
  templateUrl: './dice.component.html',
  styleUrl: './dice.component.css',
})
export class DiceComponent implements OnDestroy {
  value = input<number | null>(null);
  color = input<string>('WHITE');
  rolling = input(false);
  faces = input<6 | 8>(6);

  private readonly SIZE = 64;
  private readonly svc = inject(DiceSvgService);
  private readonly el = inject(ElementRef);

  // Per-instance random parameters, fixed for the lifetime of the component
  private readonly period = 0.5 + Math.random() * 0.4; // 0.5 – 0.9 s
  private readonly phaseOffset = -(Math.random() * this.period); // negative delay → random start frame
  private readonly stopDelay = Math.floor(Math.random() * 1500); // 300 – 2499 ms extra roll time

  private stopTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly animating = signal(false);
  private readonly svgUrl = signal<string | null>(null);

  constructor() {
    // Set CSS custom properties once on the host element so the CSS animation
    // picks them up via var(). Setting them here avoids Angular ever touching
    // the `animation` property itself, which would restart the running animation.
    const host = this.el.nativeElement as HTMLElement;
    host.style.setProperty('--dice-period', `${this.period}s`);
    host.style.setProperty('--dice-phase', `${this.phaseOffset}s`);

    effect(() => {
      const r = this.rolling();
      if (r) {
        if (this.stopTimer !== null) {
          clearTimeout(this.stopTimer);
          this.stopTimer = null;
        }
        this.animating.set(true);
      } else {
        this.stopTimer = setTimeout(() => {
          this.animating.set(false);
          this.stopTimer = null;
        }, this.stopDelay);
      }
    });

    effect(() => {
      const color = this.color().toLowerCase() as DiceColor;
      const faces = this.faces();
      const type = this.animating() ? 'anim' : 'results';
      void this.svc.getUrl(faces, color, type).then((url) => this.svgUrl.set(url));
    });
  }

  ngOnDestroy() {
    if (this.stopTimer !== null) clearTimeout(this.stopTimer);
  }

  animClass = computed(() => {
    if (!this.animating()) return {};
    return { [`rolling-d${this.faces()}`]: true };
  });

  diceStyle = computed(() => {
    const url = this.svgUrl();
    if (!url) return {};
    const S = this.SIZE;
    const v = this.value();
    const anim = this.animating();
    return {
      'background-image': `url("${url}")`,
      'background-position': anim || v == null ? '0px 0px' : `${-(v - 1) * S}px 0px`,
    };
  });
}
