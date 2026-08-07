import { SheetRow } from '../../generated';
import { closedLastLockedRow } from './played-progress.util';

function row(id: string, locked: boolean): SheetRow {
  return { id, cells: [], ...(locked ? { lock: { color: 'RED' } } : {}) } as SheetRow;
}

describe('closedLastLockedRow', () => {
  it('closes the last locked row, skipping a trailing bonus strip that cannot be closed', () => {
    const rows = [row('red', true), row('blue', true), row('bonus-strip', false)];
    expect(closedLastLockedRow(rows)).toEqual({ blue: 'debug' });
  });
});
