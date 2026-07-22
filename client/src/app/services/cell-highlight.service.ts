import { Injectable } from '@angular/core';
import { CellTag, GameState } from '../../generated/model/models';
import { computeBonusBProgress } from '../row/bonus-b.util';

const EMPTY = new Set<string>();

// Cell tags whose crossability triggers the bonus sound once the dice settle: lucky number and
// lucky cross. Bonus A/B boxes are excluded here — they are almost always crossable, so they would
// fire the sound constantly; instead they sound on the actual cross (see justCrossedBonusBox).
const BONUS_TAGS = new Set<string>([CellTag.TypeEnum.LUCKY_NUMBER, CellTag.TypeEnum.LUCKY_CROSS]);

// Bonus A (box) and Bonus B (box) tags: these sound when the player crosses one, not when crossable.
const BONUS_BOX_TAGS = new Set<string>([CellTag.TypeEnum.BONUS_BOX, CellTag.TypeEnum.BONUS_B]);

@Injectable({ providedIn: 'root' })
export class CellHighlightService {
  maxedColors(state: GameState, pid: string): Set<string> {
    const layout = state.sheetLayouts[pid];
    const progress = state.sheetProgress[pid];
    if (!layout) return EMPTY;

    const hasBonus = layout.rows.some((row) =>
      row.cells.some((c) => c.tags?.some((t) => t.type === CellTag.TypeEnum.SECONDARY_COLOR)),
    );
    if (!hasBonus) return EMPTY;

    const regularRow = layout.rows.find((r) => r.lock != null);
    const cap = regularRow ? regularRow.cells.length + 4 : null;
    if (cap === null) return EMPTY;

    const counts: Record<string, number> = {};
    for (const row of layout.rows) {
      if (row.luckyRow) continue;
      const rowState = progress?.rowStates[row.id];
      if (!rowState) continue;
      const crossed = new Set(rowState.crossedCells ?? []);
      for (const cell of row.cells) {
        if (!crossed.has(cell.id)) continue;
        counts[cell.color] = (counts[cell.color] ?? 0) + 1;
        for (const tag of cell.tags ?? []) {
          if (tag.type === CellTag.TypeEnum.SECONDARY_COLOR && tag.secondaryColor) {
            counts[tag.secondaryColor] = (counts[tag.secondaryColor] ?? 0) + 1;
          }
        }
      }
      if (rowState.lockCrossed && row.lock) {
        counts[row.lock.color] = (counts[row.lock.color] ?? 0) + 1;
      }
    }

    const maxed = new Set<string>();
    for (const [color, count] of Object.entries(counts)) {
      if (count >= cap) maxed.add(color);
    }
    return maxed;
  }

  offlineClickable(state: GameState, pid: string): Set<string> {
    const layout = state.sheetLayouts[pid];
    const progress = state.sheetProgress[pid];
    const closed = state.closedRows ?? {};
    if (!layout) return EMPTY;

    const result = new Set<string>();
    for (const row of layout.rows) {
      if (closed[row.id]) continue;
      const crossed = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
      const lastPos = Math.max(-1, ...row.cells.filter((c) => crossed.has(c.id)).map((c) => c.position));
      for (const cell of row.cells) {
        if (!crossed.has(cell.id) && cell.position > lastPos) result.add(cell.id);
      }
    }
    return result;
  }

  /**
   * True when this player can cross a lucky-number or lucky-cross cell, or the roll's white+white
   * sum hits one of their Longo bonus numbers. Drives the bonus sound that plays once the dice have
   * settled. Bonus A/B boxes are excluded — they sound when crossed, not when merely crossable.
   */
  hasCrossableBonus(state: GameState | null | undefined, pid: string): boolean {
    if (!state) return false;
    const clickable = new Set((state.availableMoves?.[pid] ?? []).map((m) => m.cellId));
    for (const row of state.sheetLayouts[pid]?.rows ?? []) {
      for (const cell of row.cells) {
        if (clickable.has(cell.id) && cell.tags?.some((t) => BONUS_TAGS.has(t.type))) return true;
      }
    }
    const roll = state.turnState?.currentRoll;
    return !!roll && (state.bonusNumbers?.[pid] ?? []).includes(roll.white1 + roll.white2);
  }

  /** Rows this player has locked (their own lock cell is crossed). */
  private lockCount(state: GameState | null | undefined, pid: string): number {
    const rowStates = state?.sheetProgress?.[pid]?.rowStates ?? {};
    return Object.values(rowStates).filter((r) => r.lockCrossed).length;
  }

  /** True when this player just crossed a lock cell of their own (their lock count went up). */
  crossedOwnLock(prev: GameState | null | undefined, next: GameState | null | undefined, pid: string): boolean {
    return this.lockCount(next, pid) > this.lockCount(prev, pid);
  }

  /** How many crossed cells carry a Bonus A or Bonus B box tag — the bonus sound fires when this rises. */
  private bonusBoxesCrossed(state: GameState | null | undefined, pid: string): number {
    const layout = state?.sheetLayouts?.[pid];
    const progress = state?.sheetProgress?.[pid];
    if (!layout) return 0;
    let count = 0;
    for (const row of layout.rows) {
      const crossed = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
      for (const cell of row.cells) {
        if (crossed.has(cell.id) && cell.tags?.some((t) => BONUS_BOX_TAGS.has(t.type))) count++;
      }
    }
    return count;
  }

  /** True when this player just crossed a Bonus A or Bonus B box (their crossed-box count rose). */
  justCrossedBonusBox(prev: GameState | null | undefined, next: GameState | null | undefined, pid: string): boolean {
    return this.bonusBoxesCrossed(next, pid) > this.bonusBoxesCrossed(prev, pid);
  }

  /** Total punishments across all players — the punishment sound fires when this rises. */
  private totalPunishments(state: GameState | null | undefined): number {
    return Object.values(state?.sheetProgress ?? {}).reduce((n, p) => n + (p.punishments ?? 0), 0);
  }

  newPunishmentTaken(prev: GameState | null | undefined, next: GameState | null | undefined): boolean {
    return this.totalPunishments(next) > this.totalPunishments(prev);
  }

  /** True when this player just completed a Bonus B kind (any kind reached its 2/2 requirement). */
  bonusBJustCompleted(prev: GameState | null | undefined, next: GameState | null | undefined, pid: string): boolean {
    const before = computeBonusBProgress(prev?.sheetLayouts?.[pid], prev?.sheetProgress?.[pid]);
    const after = computeBonusBProgress(next?.sheetLayouts?.[pid], next?.sheetProgress?.[pid]);
    return Object.keys(after).some((kind) => after[kind] === 2 && (before[kind] ?? 0) < 2);
  }
}
