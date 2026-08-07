import { SheetProgress, SheetRow } from '../../generated';

/** Cross every other cell, so a crossed cell always sits next to an untouched one of the same kind. */
const CROSS_EVERY_NTH_CELL = 2;
const PUNISHMENTS_TAKEN = 2;

/**
 * A deterministic half-played progress for a layout: every other cell crossed and every lock
 * crossed. It lets the mini-sheet debug catalog show each variant's crossed states beside its
 * pristine ones, without anyone having to play a game to reach them.
 */
export function playedProgress(rows: SheetRow[]): SheetProgress {
  const rowStates: SheetProgress['rowStates'] = {};
  for (const row of rows) {
    rowStates[row.id] = {
      crossedCells: row.cells.filter((_, index) => index % CROSS_EVERY_NTH_CELL === 0).map((cell) => cell.id),
      lockCrossed: row.lock != null,
    };
  }
  return { rowStates, punishments: PUNISHMENTS_TAKEN };
}

/**
 * Closes the last lockable row — the one state the progress above cannot express. Only rows with a
 * lock can be closed, and picking a locked one keeps a trailing bonus bar/strip out of the dimming,
 * which would otherwise hide the row this page most needs to show.
 */
export function closedLastLockedRow(rows: SheetRow[]): Record<string, string> {
  const lastLocked = rows.filter((row) => row.lock != null).at(-1);
  return lastLocked ? { [lastLocked.id]: 'debug' } : {};
}
