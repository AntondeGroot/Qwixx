import { computed, Injectable, signal } from '@angular/core';
import { GameState, MoveRequest, MoveType, SheetCell, SheetRow, TurnPhase } from '../../generated/model/models';

@Injectable({ providedIn: 'root' })
export class AutoLockService {
  readonly pending = signal<{ rowId: string; autoLock: boolean; cellId: string } | null>(null);

  // Row ID of the row the player has committed to closing (autoLock=true).
  // Used to show the lock ✕ immediately after confirming YES on the second-to-last Longo cell,
  // before the server declares the lock intent.
  readonly pendingRowId = computed(() => {
    const p = this.pending();
    return p?.autoLock ? p.rowId : null;
  });

  clear(): void {
    this.pending.set(null);
  }

  // Called after clicking a closing-eligible cell to arm auto-lock if the click would enable
  // a lock declaration. Sets pending to null when the click doesn't qualify.
  setupIfEligible(row: SheetRow, cell: SheetCell, state: GameState, pid: string, pendingCellIds: Set<string>): void {
    if (!row.lock || !this.wouldEnableLock(row, cell.id, state, pid, pendingCellIds)) {
      this.pending.set(null);
      return;
    }
    const lastClosingId = row.lock.closingCells[row.lock.closingCells.length - 1];
    this.pending.set({ rowId: row.id, autoLock: cell.id === lastClosingId, cellId: cell.id });
  }

  wouldEnableLock(
    row: SheetRow,
    newCellId: string,
    state: GameState,
    pid: string,
    pendingCellIds: Set<string>,
  ): boolean {
    if (!row.lock) return false;
    const permanent = new Set(state.sheetProgress[pid]?.rowStates[row.id]?.crossedCells ?? []);
    const all = new Set([...permanent, ...pendingCellIds, newCellId]);
    return row.lock.closingCells.some((id) => all.has(id));
  }

  // Returns a DECLARE_LOCK_INTENT move when auto-lock should fire, null otherwise.
  // Clears stale pending state as a side effect.
  checkAndConsume(state: GameState, pid: string): MoveRequest | null {
    const pending = this.pending();
    if (!pending) return null;

    // A new turn or game-over means the triggering cross was never persisted — discard.
    if (state.gameOver || state.turnState?.phase === TurnPhase.ROLL) {
      this.pending.set(null);
      return null;
    }

    const { rowId, autoLock, cellId } = pending;
    const permanent = new Set(state.sheetProgress?.[pid]?.rowStates?.[rowId]?.crossedCells ?? []);
    const pendingCrosses = new Set(state.turnState?.pendingCrosses?.[pid] ?? []);
    if (!permanent.has(cellId) && !pendingCrosses.has(cellId)) {
      // Triggering cross was undone — discard.
      this.pending.set(null);
      return null;
    }

    if (!this.canDeclare(state, rowId, pid)) {
      // Triggering cell is present but prerequisites not yet met (e.g. Longo "15" crossed,
      // "16" not yet) — wait for the next state update.
      return null;
    }

    this.pending.set(null);
    return autoLock ? { moveType: MoveType.DECLARE_LOCK_INTENT, rowId } : null;
  }

  private canDeclare(state: GameState, rowId: string, pid: string): boolean {
    const row = state.sheetLayouts?.[pid]?.rows.find((r) => r.id === rowId);
    if (!row?.lock) return false;
    if ((state.closedRows ?? {})[rowId]) return false;
    if ((state.pendingClosures ?? {})[rowId]) return false; // already declared
    const rowState = state.sheetProgress?.[pid]?.rowStates?.[rowId];
    if (rowState?.lockCrossed) return false;
    const permanent = new Set(rowState?.crossedCells ?? []);
    const pending = new Set(state.turnState?.pendingCrosses?.[pid] ?? []);
    const closing = row.lock.closingCells;
    const last = closing[closing.length - 1];
    if (last === undefined) return false; // no closing cells → cannot lock

    if (permanent.has(last) || pending.has(last)) return true;

    // Second-to-last closing cell pending (Longo "15"/"3" YES scenario) → can declare.
    if (closing.length > 1) {
      const secondLast = closing[closing.length - 2]!; // length > 1 guarantees this index
      if (pending.has(secondLast)) {
        return permanent.size + 1 >= row.lock.minCrosses;
      }
    }
    return false;
  }
}
