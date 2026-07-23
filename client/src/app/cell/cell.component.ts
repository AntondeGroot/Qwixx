import { Component, computed, input, output } from '@angular/core';

import { CellTag, SheetCell } from '../../generated/model/models';
import { bonusBShapeOf, bonusKindOf } from '../row/bonus-b.util';
import { SilverMarkComponent } from '../silver-mark/silver-mark.component';

@Component({
  selector: 'app-cell',
  imports: [SilverMarkComponent],
  templateUrl: './cell.component.html',
  styleUrl: './cell.component.css',
  host: {
    '[class.host-big-points-clickable]': '!!secondaryColor() && !!(showClickable() ?? clickable())',
    '[class.host-ww]': '!!secondaryColor() && showDieHint()',
    '[class.host-xchange-clickable]': '!!xChangeValues() && !!(showClickable() ?? clickable())',
    '[class.host-lucky-number-clickable]': '!!luckyNumberBonusPoints() && !!(showClickable() ?? clickable())',
    '[class.host-lucky-number-ww]': '!!luckyNumberBonusPoints() && showDieHint()',
    '[class.host-lucky-cross]': 'isLuckyCross()',
    '[class.host-lucky-cross-clickable]': 'isLuckyCross() && !!(showClickable() ?? clickable())',
  },
})
export class CellComponent {
  cell = input.required<SheetCell>();
  crossed = input(false);
  pending = input(false);
  clickable = input(false);
  autoCrossTarget = input(false);
  // Double A twin: render a small hollow cell (white fill, coloured border, no number).
  outline = input(false);
  // Bonus B strip indicators: suppress the row-colour background (they're neutral tiles).
  neutral = input(false);
  showClickable = input<boolean | undefined>(undefined);
  showDieHint = input(false);

  maxedColors = input<Set<string>>(new Set());

  clicked = output<void>();

  hasTag = computed(() => (type: CellTag.TypeEnum) => this.cell().tags.some((t) => t.type === type));

  readonly TagEnum = CellTag.TypeEnum;

  secondaryColor = computed(
    () => this.cell().tags.find((t) => t.type === CellTag.TypeEnum.SECONDARY_COLOR)?.secondaryColor ?? null,
  );

  xChangeValues = computed(() => {
    const tag = this.cell().tags.find((t) => t.type === CellTag.TypeEnum.X_CHANGE);
    return tag ? { a: tag.valueA!, b: tag.valueB! } : null;
  });

  luckyNumberBonusPoints = computed(() => {
    const tag = this.cell().tags.find((t) => t.type === CellTag.TypeEnum.LUCKY_NUMBER);
    return tag ? tag.amount! : null;
  });

  isLuckyCross = computed(() => this.cell().tags.some((t) => t.type === CellTag.TypeEnum.LUCKY_CROSS));

  // Bonus B: the kind of bonus box (or null). Drives the background shape + corner chip.
  bonusBKind = computed(() => bonusKindOf(this.cell()) ?? null);

  // Background shape: 'triangle' (fewest-row bonuses), 'plus' (add bonuses), 'shield' (no-penalty).
  bonusBShape = computed(() => bonusBShapeOf(this.bonusBKind() ?? undefined));

  // Corner chip text (null when the mark uses the colour chip or has no tag).
  bonusBTag = computed(() => {
    const K = CellTag.BonusKindEnum;
    switch (this.bonusBKind()) {
      case K.FEWEST_TWO:
        return '✕✕';
      case K.DOUBLE_FEWEST:
        return '×2';
      case K.PLUS_13:
        return '+13';
      default:
        return null;
    }
  });

  bonusBQuad = computed(() => this.bonusBKind() === CellTag.BonusKindEnum.ONE_EACH);

  primaryMaxed = computed(() => !!this.secondaryColor() && this.maxedColors().has(this.cell().color));
  secondaryMaxed = computed(() => {
    const sc = this.secondaryColor();
    return sc !== null && this.maxedColors().has(sc);
  });
}
