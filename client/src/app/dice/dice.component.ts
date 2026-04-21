import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { NgClass, NgStyle } from '@angular/common';
import { DiceSvgService } from './dice-svg.service';

@Component({
  selector: 'app-dice',
  imports: [NgClass, NgStyle],
  templateUrl: './dice.component.html',
  styleUrl: './dice.component.css'
})
export class DiceComponent {
  value   = input<number | null>(null);
  color   = input<string>('WHITE');
  rolling = input(false);
  faces   = input<6 | 8>(6);

  private readonly SIZE = 64;
  private readonly svc  = inject(DiceSvgService);

  private svgUrl = signal<string | null>(null);

  constructor() {
    effect(() => {
      const color = this.color().toLowerCase() as any;
      const faces = this.faces();
      const type  = this.rolling() ? 'anim' : 'results';
      this.svc.getUrl(faces, color, type).then(url => this.svgUrl.set(url));
    });
  }

  animClass = computed(() => {
    if (!this.rolling()) return {};
    return { [`rolling-d${this.faces()}`]: true };
  });

  diceStyle = computed(() => {
    const url = this.svgUrl();
    if (!url) return {};
    const S = this.SIZE;
    const v = this.value();
    return {
      'background-image':    `url("${url}")`,
      'background-position': this.rolling() ? '0px 0px' : (v != null ? `${-(v - 1) * S}px 0px` : '0px 0px'),
    };
  });
}