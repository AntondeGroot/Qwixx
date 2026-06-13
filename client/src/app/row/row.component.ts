import { Component, computed, inject, input, output } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

import { CellTag, RowState, SheetRow } from '../../generated/model/models';
import { CellComponent } from '../cell/cell.component';

@Component({
  selector: 'app-row',
  imports: [CellComponent],
  templateUrl: './row.component.html',
  styleUrl: './row.component.css',
})
export class RowComponent {
  private readonly translate = inject(TranslateService);

  row = input.required<SheetRow>();
  rowState = input<RowState | null>(null);
  closed = input(false);
  clickableCellIds = input<Set<string>>(new Set());
  showClickableCellIds = input<Set<string> | null>(null);
  whiteWhiteClickableCellIds = input<Set<string>>(new Set());
  pendingCellIds = input<Set<string>>(new Set());
  isDeclarantLockPending = input(false);
  isPendingAutoLock = input(false);

  lockClickable = input(false);
  maxedColors = input<Set<string>>(new Set());

  cellClicked = output<string>();
  lockClicked = output<void>();

  // Pixel x-centers of auto-cross connections to the row above / below.
  // Computed by the board and passed in so the row knows which direction to draw.
  connectorOffsetsAbove = input<number[]>([]);
  connectorOffsetsBelow = input<number[]>([]);
  // True when the row immediately above/below is a bonus row (e.g. Big Points).
  // Used to extend the connector line through the full height of the bonus row.
  hasBonusRowAbove = input(false);
  hasBonusRowBelow = input(false);
  showLuckyCrossHint = input(false);

  closingEligibleCells = computed(() => this.row().cells.filter((c) => c.closingEligible));

  isXChangeRow = computed(() => this.row().cells.some((c) => c.tags.some((t) => t.type === CellTag.TypeEnum.X_CHANGE)));

  isLuckyRow = computed(() => this.row().luckyRow === true);
  luckyTarget = computed(() => this.row().luckyTarget);
  luckyLabel = computed(() => `LUCKY ${this.luckyTarget()}`);

  // Formula: (n+1)×44 + n×4 + 16 = 48n+60  →  Standard 9 cells = 492px, Longo 13 cells = 684px
  xChangeRowWidth = computed(() => (this.isXChangeRow() ? `${48 * this.row().cells.length + 60}px` : null));

  // For bonus rows (no lock), the last 1 (Standard) or 2 (Longo) cells are pulled into a
  // visual alignment zone so they line up with the closing cells of adjacent regular rows.
  private readonly bonusZoneCellCount = computed(() => {
    if (this.row().lock != null) return 0;
    if (this.isXChangeRow()) return 0;
    if (this.isLuckyRow()) return 0; // lucky number row has no alignment zone
    const total = this.row().cells.length;
    return total > 12 ? 2 : 1;
  });

  regularCells = computed(() => {
    const cells = this.row().cells.filter((c) => !c.closingEligible);
    const n = this.bonusZoneCellCount();
    return n > 0 ? cells.slice(0, -n) : cells;
  });

  bonusZoneCells = computed(() => {
    const n = this.bonusZoneCellCount();
    if (n === 0) return [];
    return this.row()
      .cells.filter((c) => !c.closingEligible)
      .slice(-n);
  });

  t(key: string): string {
    return this.translate.instant(key);
  }

  isCrossed(cellId: string): boolean {
    return this.rowState()?.crossedCells.includes(cellId) ?? false;
  }
}
