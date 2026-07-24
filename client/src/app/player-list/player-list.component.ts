import { Component, computed, input } from '@angular/core';
import { environment } from '../../environments/environment';
import { Player } from '../../generated';
import { SheetCell } from '../../generated';
import { SheetLayout } from '../../generated';
import { SheetProgress } from '../../generated';
import { SheetRow } from '../../generated';
import { TurnPhase } from '../../generated';
import { TurnState } from '../../generated';
import { BonusBShape, bonusBShapeOf, bonusKindOf } from '../row/bonus-b.util';

@Component({
  selector: 'app-player-list',
  imports: [],
  templateUrl: './player-list.component.html',
  styleUrl: './player-list.component.css',
})
export class PlayerListComponent {
  players = input.required<Player[]>();
  myPlayerId = input.required<string>();
  activePlayerId = input<string | null>(null);
  turnState = input<TurnState | null>(null);
  sheetLayouts = input<Record<string, SheetLayout>>({});
  sheetProgress = input<Record<string, SheetProgress>>({});
  closedRows = input<Record<string, string>>({});
  showOtherCards = input<boolean>(true);

  otherPlayers = computed(() => this.players().filter((p) => p.id !== this.myPlayerId()));

  isActive(pid: string): boolean {
    return pid === this.activePlayerId();
  }

  shouldShowPip(pid: string): boolean {
    const turn = this.turnState();
    if (!turn) return false;
    if (pid === turn.activePlayerId) return true;
    const phase = turn.phase;
    return phase === TurnPhase.ACTIVE_MOVE || phase === TurnPhase.PASSIVE_MOVE;
  }

  playerHasActed(pid: string): boolean {
    const turn = this.turnState();
    if (!turn) return false;
    if (pid === turn.activePlayerId) {
      return turn.phase !== TurnPhase.ROLL && turn.phase !== TurnPhase.ACTIVE_MOVE;
    }
    return !(turn.passivePlayerQueue ?? []).includes(pid);
  }

  rowsFor(pid: string): SheetRow[] {
    return this.sheetLayouts()[pid]?.rows ?? [];
  }

  isCrossed(pid: string, rowId: string, cellId: string): boolean {
    return this.sheetProgress()[pid]?.rowStates?.[rowId]?.crossedCells?.includes(cellId) ?? false;
  }

  isRowClosed(rowId: string): boolean {
    return rowId in this.closedRows();
  }

  punishmentsFor(pid: string): number {
    return this.sheetProgress()[pid]?.punishments ?? 0;
  }

  profilePicUrl(index: string): string {
    return `${environment.lobbyUrl.replace(/\/$/, '')}/profile-pic/${index}`;
  }

  initials(name: string): string {
    return name
      .split(' ')
      .map((w) => w[0])
      .join('')
      .slice(0, 2)
      .toUpperCase();
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
