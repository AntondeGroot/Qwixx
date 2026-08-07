import { Component, computed, input } from '@angular/core';
import { SheetCell } from '../../generated';
import { SheetProgress } from '../../generated';
import { SheetRow } from '../../generated';
import { BonusBShape, bonusBShapeOf, bonusKindOf } from '../row/bonus-b.util';

/**
 * The thumbnail of one player's sheet: a 9px square per cell, tinted the way the full board tints
 * its cells. Shown in the player list for every other player, and on the /mini-catalog debug page
 * for every sheet variant — both render this same component, so the debug page can't drift.
 */
@Component({
  selector: 'app-mini-sheet',
  imports: [],
  templateUrl: './mini-sheet.component.html',
  styleUrl: './mini-sheet.component.css',
})
export class MiniSheetComponent {
  rows = input.required<SheetRow[]>();
  progress = input<SheetProgress | null>(null);
  /** Closed rows keyed the way the board keeps them: row id → the id of the player who closed it. */
  closedRows = input<Record<string, string>>({});

  /** Double A/B twin → the primary cell it belongs under. Cell ids are unique across rows, so one map serves all. */
  private readonly twinByPrimaryId = computed(() => {
    const twins = new Map<string, SheetCell>();
    for (const cell of this.rows().flatMap((row) => row.cells)) {
      const primaryId = cell.tags?.find((t) => t.type === 'DOUBLE_TWIN')?.target;
      if (primaryId) twins.set(primaryId, cell);
    }
    return twins;
  });

  /**
   * The cells that get their own column. A Double A/B twin is not one of them: the full board draws
   * it stacked under its primary, and a mini sheet that instead lined the twins up after the closing
   * cell would both lose the pairing and push the closing divider into the middle of the row.
   */
  columnCells(row: SheetRow): SheetCell[] {
    return row.cells.filter((cell) => !this.isTwin(cell));
  }

  twinOf(cell: SheetCell): SheetCell | undefined {
    return this.twinByPrimaryId().get(cell.id);
  }

  /** A row with stacked twins is taller, so like the board it hangs its columns from the top. */
  hasTwins(row: SheetRow): boolean {
    return row.cells.some((cell) => this.isTwin(cell));
  }

  private isTwin(cell: SheetCell): boolean {
    return cell.tags?.some((t) => t.type === 'DOUBLE_TWIN') ?? false;
  }

  isCrossed(rowId: string, cellId: string): boolean {
    return this.progress()?.rowStates?.[rowId]?.crossedCells?.includes(cellId) ?? false;
  }

  isLockCrossed(rowId: string): boolean {
    return this.progress()?.rowStates?.[rowId]?.lockCrossed ?? false;
  }

  isRowClosed(rowId: string): boolean {
    return rowId in this.closedRows();
  }

  readonly colorHex: Record<string, string> = {
    RED: '#d32f2f',
    YELLOW: '#f9a825',
    GREEN: '#388e3c',
    BLUE: '#1565c0',
  };

  secondaryColorOf(cell: SheetCell | undefined): string | null {
    return cell?.tags?.find((t) => t.type === 'SECONDARY_COLOR')?.secondaryColor ?? null;
  }

  /**
   * The general shape of a cell's Bonus B mark, or null. The strip's cells are all tagged RED, so
   * without this the mini-sheet draws the strip as a row of red squares that says nothing about
   * which bonus is which.
   */
  bonusBShape(cell: SheetCell): BonusBShape | null {
    return bonusBShapeOf(bonusKindOf(cell));
  }

  isXChange(cell: SheetCell): boolean {
    return cell.tags?.some((t) => t.type === 'X_CHANGE') ?? false;
  }

  isXChangeRow(row: SheetRow): boolean {
    const first = row.cells[0];
    return first !== undefined && this.isXChange(first);
  }

  isLuckyNumber(cell: SheetCell): boolean {
    return cell.tags?.some((t) => t.type === 'LUCKY_NUMBER') ?? false;
  }

  isLuckyCross(cell: SheetCell): boolean {
    return cell.tags?.some((t) => t.type === 'LUCKY_CROSS') ?? false;
  }

  isLuckyRow(row: SheetRow): boolean {
    return row.luckyRow === true;
  }

  /** Mixed-colours variant: a normal colour row whose cells aren't all one colour. Its mini-cells must
   *  be tinted individually (by each cell's own colour) rather than by the row's first cell. */
  isMixedRow(row: SheetRow): boolean {
    if (this.isXChangeRow(row) || this.isLuckyRow(row) || row.bonusBar || row.bonusBStrip) return false;
    return new Set(row.cells.map((c) => c.color)).size > 1;
  }
}
