import { buildPreviewGame } from './score-preview.data';
import { computeBonusBProgress } from '../row/bonus-b.util';
import { CellTag, SheetLayout } from '../../generated/model/models';

const K = CellTag.BonusKindEnum;

/**
 * Where the bonus boxes sit in each colour row — mirrors what /game-options/preview actually returns
 * for a Bonus B game, so every kind has exactly one pair spread across two rows.
 */
const BOXES: Record<string, string[]> = {
  r: [K.PLUS_13, K.DOUBLE_FEWEST],
  y: [K.NO_PENALTY, K.ONE_EACH, K.FEWEST_TWO],
  g: [K.FEWEST_TWO, K.NO_PENALTY],
  b: [K.DOUBLE_FEWEST, K.ONE_EACH, K.PLUS_13],
};

/** A stand-in for the real /game-options/preview layout: four colour rows plus a Bonus B strip. */
const layout = (): SheetLayout => {
  const row = (id: string, color: string) => ({
    id,
    cells: Array.from({ length: 11 }, (_, i) => {
      // Boxes sit mid-row, so "cross the first N" would only catch them by luck.
      const kind = BOXES[id][[4, 6, 8].indexOf(i)];
      return {
        id: `${id}-${i}`,
        color,
        tags: kind ? [{ type: CellTag.TypeEnum.BONUS_B, bonusKind: kind }] : [],
      };
    }),
  });
  const strip = {
    id: 'strip',
    bonusBStrip: true,
    cells: [
      CellTag.BonusKindEnum.FEWEST_TWO,
      CellTag.BonusKindEnum.ONE_EACH,
      CellTag.BonusKindEnum.DOUBLE_FEWEST,
      CellTag.BonusKindEnum.PLUS_13,
      CellTag.BonusKindEnum.NO_PENALTY,
    ].map((kind, i) => ({
      id: `strip-${i}`,
      color: 'RED',
      tags: [{ type: CellTag.TypeEnum.BONUS_B, bonusKind: kind }],
    })),
  };
  return {
    rows: [row('r', 'RED'), row('y', 'YELLOW'), row('g', 'GREEN'), row('b', 'BLUE'), strip],
  } as unknown as SheetLayout;
};

describe('buildPreviewGame', () => {
  it('builds one player per requested seat, capped at the cast size', () => {
    expect(buildPreviewGame(layout(), 3).state.players).toHaveLength(3);
    expect(buildPreviewGame(layout(), 99).state.players.length).toBeLessThanOrEqual(6);
  });

  it('scores the doubled row at twice its triangular value', () => {
    const { scores } = buildPreviewGame(layout(), 1);
    const alice = scores['preview-0'];

    // Alice has 5 RED crosses → triangular(5) = 15, doubled to 30.
    expect(alice.doubledColor).toBe('RED');
    expect(alice.pointsPerColor['RED']).toBe(30);
    expect(alice.pointsPerColor['YELLOW']).toBe(15); // 5 crosses, undoubled
  });

  it('zeroes the punishment for the shielded player, so the shield has something to explain', () => {
    const { scores } = buildPreviewGame(layout(), 2);

    expect(scores['preview-0'].noPenalty).toBe(true);
    expect(scores['preview-0'].punishmentPoints).toBe(0);
    expect(scores['preview-1'].noPenalty).toBe(false);
    expect(scores['preview-1'].punishmentPoints).toBe(-5);
  });

  it('keeps total consistent with the parts, so the count-up lands where the table says', () => {
    const { scores } = buildPreviewGame(layout(), 6);

    for (const sc of Object.values(scores)) {
      const colours = Object.values(sc.pointsPerColor).reduce((s, v) => s + v, 0);
      expect(sc.total).toBe(colours + sc.bonusPoints + sc.punishmentPoints);
    }
  });

  it('has the ×2 flip the lead within the first column — the whole point of the preview', () => {
    const { scores } = buildPreviewGame(layout(), 2);
    // Ranks sort on the running total, and RED counts first, so the flip must happen inside RED:
    // anything decided by later columns would not read as the doubling's doing.
    const aliceRed = scores['preview-0'].pointsPerColor['RED'];
    const bobRed = scores['preview-1'].pointsPerColor['RED'];

    expect(aliceRed / 2).toBeLessThan(bobRed); // Bob leads once RED has counted up plain
    expect(aliceRed).toBeGreaterThan(bobRed); // the double takes Alice past him
  });

  it("doubles a row that is really that player's fewest, as the bonus requires", () => {
    const { scores } = buildPreviewGame(layout(), 6);
    const order = ['RED', 'YELLOW', 'GREEN', 'BLUE']; // the server's tie-break order

    for (const sc of Object.values(scores)) {
      if (!sc.doubledColor) continue;
      const fewest = Math.min(...order.map((c) => sc.crossesPerColor[c] ?? 0));
      const expected = order.find((c) => (sc.crossesPerColor[c] ?? 0) === fewest);
      expect(sc.doubledColor).toBe(expected);
    }
  });

  it('marks exactly the strip indicators the player achieved', () => {
    const { state } = buildPreviewGame(layout(), 1);
    const progress = state.sheetProgress['preview-0'];

    // Alice has the ×2, the shield and the +13 — three of the five indicators.
    expect(progress.rowStates['strip'].crossedCells).toEqual(expect.arrayContaining(['strip-2', 'strip-3', 'strip-4']));
    expect(progress.rowStates['strip'].crossedCells).toHaveLength(3);
  });

  it('backs every achieved bonus with both its boxes, so the strip counters read 2/2', () => {
    const l = layout();
    const { state, scores } = buildPreviewGame(l, 6);

    for (const [id, sc] of Object.entries(scores)) {
      const progress = state.sheetProgress[id];
      const counts = computeBonusBProgress(l, progress);
      const claimed: string[] = [
        ...(sc.doubledColor ? [CellTag.BonusKindEnum.DOUBLE_FEWEST] : []),
        ...(sc.noPenalty ? [CellTag.BonusKindEnum.NO_PENALTY] : []),
        ...(sc.bonusPoints > 0 ? [CellTag.BonusKindEnum.PLUS_13] : []),
      ];

      // A bonus the score card pays out must show a completed pair on the board...
      for (const kind of claimed) expect(counts[kind] ?? 0).toBe(2);
      // ...and one it does not pay out must never look complete.
      for (const kind of Object.values(CellTag.BonusKindEnum)) {
        if (!claimed.includes(kind)) expect(counts[kind] ?? 0).toBeLessThan(2);
      }
    }
  });

  it('crosses each colour row to match its score, so the board below the table agrees', () => {
    const { state, scores } = buildPreviewGame(layout(), 1);
    const progress = state.sheetProgress['preview-0'];

    expect(progress.rowStates['r'].crossedCells).toHaveLength(scores['preview-0'].crossesPerColor['RED']);
    expect(progress.rowStates['b'].crossedCells).toHaveLength(scores['preview-0'].crossesPerColor['BLUE']);
  });
});
