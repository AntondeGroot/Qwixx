import { Injectable } from '@angular/core';
import { CellTag, GameState } from '../../generated/model/models';

const EMPTY = new Set<string>();

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
}
