import { Component, computed, inject, input, output } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

import { CellTag, RowState, SheetCell, SheetRow } from '../../generated/model/models';
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
  // 'A' → twin cell stacked below its primary; 'B' → twin placed diagonally; null → no double variant.
  doubleVariant = input<'A' | 'B' | null>(null);

  private isTwin(c: SheetCell): boolean {
    return c.tags.some((t) => t.type === CellTag.TypeEnum.DOUBLE_TWIN);
  }

  // Map from a primary cell id to its Double A/B twin cell (if any).
  twinByPrimaryId = computed(() => {
    const map = new Map<string, SheetCell>();
    for (const c of this.row().cells) {
      const tag = c.tags.find((t) => t.type === CellTag.TypeEnum.DOUBLE_TWIN);
      if (tag?.target) map.set(tag.target, c);
    }
    return map;
  });

  closingEligibleCells = computed(() => this.row().cells.filter((c) => c.closingEligible));

  isXChangeRow = computed(() => this.row().cells.some((c) => c.tags.some((t) => t.type === CellTag.TypeEnum.X_CHANGE)));

  isLuckyRow = computed(() => this.row().luckyRow === true);
  isBonusBar = computed(() => this.row().bonusBar === true);
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
    const cells = this.row().cells.filter((c) => !c.closingEligible && !this.isTwin(c));
    const n = this.bonusZoneCellCount();
    return n > 0 ? cells.slice(0, -n) : cells;
  });

  bonusZoneCells = computed(() => {
    const n = this.bonusZoneCellCount();
    if (n === 0) return [];
    return this.row()
      .cells.filter((c) => !c.closingEligible && !this.isTwin(c))
      .slice(-n);
  });

  t(key: string): string {
    return this.translate.instant(key);
  }

  isCrossed(cellId: string): boolean {
    return this.rowState()?.crossedCells.includes(cellId) ?? false;
  }

  // ── Double B: the whole diagonal cell is one click target ──────────────────
  // Clicking anywhere crosses the correct mark: the primary first, then the twin
  // (and undoes a pending mark if one is set), so it never matters where you click.

  /** True when either mark can be acted on (crossed, or a pending mark undone) — drives
   *  interactivity (cursor / role / click), so a just-placed mark can still be undone. */
  dbActionable(primaryId: string, twinId: string): boolean {
    return this.dbCanAct(primaryId) || this.dbCanAct(twinId);
  }

  /** True when a mark is a genuine legal move right now — drives the glow. Excludes a mark
   *  you've just placed (pending), so a crossed half stops glowing immediately instead of
   *  advertising a die you've already used. */
  dbClickable(primaryId: string, twinId: string): boolean {
    return this.dbGlow(primaryId) || this.dbGlow(twinId);
  }

  /** True when the glowing mark is a white+white move (cyan glow) vs a colour die (purple). */
  dbIsWhiteWhite(primaryId: string, twinId: string): boolean {
    const ww = this.whiteWhiteClickableCellIds();
    return (this.dbGlow(primaryId) && ww.has(primaryId)) || (this.dbGlow(twinId) && ww.has(twinId));
  }

  onDoubleBClick(primaryId: string, twinId: string): void {
    const clickable = this.clickableCellIds();
    const pending = this.pendingCellIds();
    // Cross the primary first, then the twin; otherwise undo the last-placed pending mark.
    if (clickable.has(primaryId)) this.cellClicked.emit(primaryId);
    else if (clickable.has(twinId)) this.cellClicked.emit(twinId);
    else if (pending.has(twinId)) this.cellClicked.emit(twinId);
    else if (pending.has(primaryId)) this.cellClicked.emit(primaryId);
  }

  private dbCanAct(id: string): boolean {
    return this.clickableCellIds().has(id) || this.pendingCellIds().has(id);
  }

  // A mark glows only when it's a legal move AND not already placed this turn (pending).
  private dbGlow(id: string): boolean {
    return this.clickableCellIds().has(id) && !this.pendingCellIds().has(id);
  }
}
