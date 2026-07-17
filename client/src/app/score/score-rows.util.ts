import { CellTag, SheetRow } from '../../generated/model/models';

const isXChangeRow = (r: SheetRow): boolean =>
  r.cells.some((c) => c.tags.some((t) => t.type === CellTag.TypeEnum.X_CHANGE));
const isLuckyRow = (r: SheetRow): boolean => r.luckyRow === true;

/**
 * The four scoring colour rows only — excludes Big Points bonus rows, X-Change, Lucky Number,
 * and the Bonus A bonus bar / Bonus B strip (which are not colour columns in the score animation).
 */
export const isScoringColourRow = (r: SheetRow): boolean =>
  !r.bonusRow && !r.bonusBar && !r.bonusBStrip && !isXChangeRow(r) && !isLuckyRow(r);
