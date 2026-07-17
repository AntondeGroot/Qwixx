import { CellTag, SheetCell, SheetLayout, SheetProgress } from '../../generated/model/models';

/** The Bonus B kind a cell carries, or undefined for an ordinary cell. */
export function bonusKindOf(cell: SheetCell): CellTag.BonusKindEnum | undefined {
  return cell.tags.find((t) => t.type === CellTag.TypeEnum.BONUS_B)?.bonusKind;
}

/** The general shape a Bonus B kind is drawn as, shared by every renderer so they cannot disagree. */
export type BonusBShape = 'triangle' | 'plus' | 'shield';

export function bonusBShapeOf(kind: CellTag.BonusKindEnum | undefined): BonusBShape | null {
  const K = CellTag.BonusKindEnum;
  switch (kind) {
    case K.FEWEST_TWO: // the two "fewest row" bonuses point at a row…
    case K.DOUBLE_FEWEST:
      return 'triangle';
    case K.ONE_EACH: // …the two "you gain" bonuses add something…
    case K.PLUS_13:
      return 'plus';
    case K.NO_PENALTY: // …and the shield protects.
      return 'shield';
    default:
      return null;
  }
}

/**
 * For each Bonus B kind, how many of its two boxes are crossed in the colour rows (0–2).
 * Keyed by the kind string (e.g. "FEWEST_TWO"). Used to show a N/2 counter on the strip.
 */
export function computeBonusBProgress(
  layout: SheetLayout | undefined | null,
  progress: SheetProgress | undefined | null,
): Record<string, number> {
  const counts: Record<string, number> = {};
  if (!layout) return counts;
  for (const row of layout.rows) {
    if (row.bonusBStrip) continue; // count only the colour-row boxes, not the strip indicators
    const crossed = progress?.rowStates[row.id]?.crossedCells ?? [];
    for (const cell of row.cells) {
      const kind = bonusKindOf(cell);
      if (kind && crossed.includes(cell.id)) {
        counts[kind] = (counts[kind] ?? 0) + 1;
      }
    }
  }
  return counts;
}
