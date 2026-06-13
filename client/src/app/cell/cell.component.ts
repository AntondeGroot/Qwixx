import { Component, computed, input, output } from '@angular/core';

import { CellTag, SheetCell } from '../../generated/model/models';

@Component({
  selector: 'app-cell',
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

  primaryMaxed = computed(() => !!this.secondaryColor() && this.maxedColors().has(this.cell().color));
  secondaryMaxed = computed(() => {
    const sc = this.secondaryColor();
    return sc !== null && this.maxedColors().has(sc);
  });
}
