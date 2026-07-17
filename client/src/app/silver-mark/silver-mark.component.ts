import { Component, computed, input } from '@angular/core';
import { BonusBShape } from '../row/bonus-b.util';

// SVG ids live in the global document, so each mark needs its own to keep the gradient and clip
// references pointing at its own <defs> rather than another mark's.
let uidSeq = 0;

/** The three Bonus B silhouettes, on a 0 0 44 44 viewBox. */
const SHAPE_PATHS: Record<BonusBShape, string> = {
  triangle: 'M7 22 L37 7 L37 37 Z',
  plus: 'M14 6 H30 V14 H38 V30 H30 V38 H14 V30 H6 V14 H14 Z',
  shield: 'M22 6 L36 11 V21 C36 29 30 34.5 22 38 C14 34.5 8 29 8 21 V11 Z',
};

/**
 * A Bonus B mark in brushed silver with a gleam: the tiles use all three shapes behind their number,
 * while the board and score screen use the shield alone to flag "mis-rolls cost nothing".
 *
 * Size it from the host (e.g. `.pl-shield { width: 14px }`) — the SVG scales to fit. The host can
 * also set `--silver-stroke-width` to thin the rim for small renderings.
 */
@Component({
  selector: 'app-silver-mark',
  template: `
    <svg viewBox="0 0 44 44" aria-hidden="true">
      <defs>
        <path [attr.id]="uid + '-shape'" [attr.d]="path()" />
        <!-- Brushed silver; stop colours come from --silver-* in styles.css. -->
        <linearGradient class="silver-grad" [attr.id]="uid + '-silver'" x1="0" y1="0" x2="0.35" y2="1">
          <stop offset="0%" />
          <stop offset="22%" />
          <stop offset="48%" />
          <stop offset="58%" />
          <stop offset="82%" />
          <stop offset="100%" />
        </linearGradient>
        <linearGradient [attr.id]="uid + '-sheen'" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stop-color="#fff" stop-opacity="0" />
          <stop offset="50%" stop-color="#fff" stop-opacity="0.85" />
          <stop offset="100%" stop-color="#fff" stop-opacity="0" />
        </linearGradient>
        <clipPath [attr.id]="uid + '-clip'">
          <use [attr.href]="'#' + uid + '-shape'" />
        </clipPath>
      </defs>
      <use class="face" [attr.href]="'#' + uid + '-shape'" [attr.fill]="'url(#' + uid + '-silver)'" />
      <g [attr.clip-path]="'url(#' + uid + '-clip)'">
        <rect class="silver-sheen" x="0" y="-30" width="9" height="100" [attr.fill]="'url(#' + uid + '-sheen)'" />
      </g>
    </svg>
  `,
  styles: `
    :host {
      display: inline-block;
    }
    svg {
      display: block;
      width: 100%;
      height: 100%;
    }
    .face {
      stroke: var(--silver-edge);
      stroke-width: var(--silver-stroke-width, 2.6);
      stroke-linejoin: round;
    }
  `,
})
export class SilverMarkComponent {
  shape = input<BonusBShape>('shield');

  readonly uid = `mark${++uidSeq}`;
  readonly path = computed(() => SHAPE_PATHS[this.shape()]);
}
