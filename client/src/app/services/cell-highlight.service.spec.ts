import { CellHighlightService } from './cell-highlight.service';
import { CellTag, GameState } from '../../generated/model/models';

/** Minimal GameState with a single row/cell for the given player 'p'. */
function stateWith(opts: {
  tag?: string;
  clickable?: boolean;
  bonusNumbers?: number[];
  white?: [number, number];
}): GameState {
  const cellId = 'c1';
  const tags = opts.tag ? [{ type: opts.tag }] : [];
  return {
    availableMoves: { p: opts.clickable === false ? [] : [{ cellId, moveType: 'CROSS_WHITE_WHITE' }] },
    sheetLayouts: {
      p: { rows: [{ id: 'r', cells: [{ id: cellId, position: 0, color: 'RED', displayValue: '5', tags }] }] },
    },
    turnState: opts.white ? { currentRoll: { white1: opts.white[0], white2: opts.white[1] } } : undefined,
    bonusNumbers: opts.bonusNumbers ? { p: opts.bonusNumbers } : undefined,
  } as unknown as GameState;
}

describe('CellHighlightService.hasCrossableBonus', () => {
  const svc = new CellHighlightService();

  it('is true when a crossable cell carries a lucky-number or lucky-cross tag', () => {
    for (const tag of [CellTag.TypeEnum.LUCKY_NUMBER, CellTag.TypeEnum.LUCKY_CROSS]) {
      expect(svc.hasCrossableBonus(stateWith({ tag }), 'p')).toBe(true);
    }
  });

  it('is false for a crossable Bonus A/B box — those sound on the cross, not on availability', () => {
    for (const tag of [CellTag.TypeEnum.BONUS_BOX, CellTag.TypeEnum.BONUS_B]) {
      expect(svc.hasCrossableBonus(stateWith({ tag }), 'p')).toBe(false);
    }
  });

  it('is false when the bonus cell is present but not crossable', () => {
    expect(svc.hasCrossableBonus(stateWith({ tag: CellTag.TypeEnum.LUCKY_CROSS, clickable: false }), 'p')).toBe(false);
  });

  it('is false for a crossable cell with no bonus tag', () => {
    expect(svc.hasCrossableBonus(stateWith({}), 'p')).toBe(false);
  });

  it('is true when the white+white sum hits a Longo bonus number', () => {
    expect(svc.hasCrossableBonus(stateWith({ bonusNumbers: [11], white: [5, 6] }), 'p')).toBe(true);
  });

  it('is false when the roll misses every bonus number', () => {
    expect(svc.hasCrossableBonus(stateWith({ bonusNumbers: [11], white: [3, 4] }), 'p')).toBe(false);
  });

  it('is false for a null state', () => {
    expect(svc.hasCrossableBonus(null, 'p')).toBe(false);
  });
});

/** State where player 'p' has `locked` of its 4 rows locked. */
function stateWithLocks(locked: number): GameState {
  const rowStates: Record<string, { lockCrossed: boolean }> = {};
  for (let i = 0; i < 4; i++) rowStates['r' + i] = { lockCrossed: i < locked };
  return { sheetProgress: { p: { rowStates } } } as unknown as GameState;
}

describe('CellHighlightService.crossedOwnLock', () => {
  const svc = new CellHighlightService();

  it('is true when this player locked another row', () => {
    expect(svc.crossedOwnLock(stateWithLocks(1), stateWithLocks(2), 'p')).toBe(true);
  });

  it('is false when this player’s lock count is unchanged', () => {
    expect(svc.crossedOwnLock(stateWithLocks(2), stateWithLocks(2), 'p')).toBe(false);
  });

  it('treats a missing previous state as zero locks', () => {
    expect(svc.crossedOwnLock(null, stateWithLocks(1), 'p')).toBe(true);
  });
});

/** State where player 'p' has `crossed` (0–2) of a single Bonus B kind's two boxes crossed. */
function bonusBState(crossed: number): GameState {
  const kind = CellTag.BonusKindEnum.FEWEST_TWO;
  const cells = ['b1', 'b2'].map((id) => ({ id, tags: [{ type: CellTag.TypeEnum.BONUS_B, bonusKind: kind }] }));
  return {
    sheetLayouts: { p: { rows: [{ id: 'r', bonusBStrip: false, cells }] } },
    sheetProgress: { p: { rowStates: { r: { crossedCells: ['b1', 'b2'].slice(0, crossed) } } } },
  } as unknown as GameState;
}

describe('CellHighlightService.bonusBJustCompleted', () => {
  const svc = new CellHighlightService();

  it('is true when a kind reaches 2/2', () => {
    expect(svc.bonusBJustCompleted(bonusBState(1), bonusBState(2), 'p')).toBe(true);
  });

  it('is false when the kind was already 2/2', () => {
    expect(svc.bonusBJustCompleted(bonusBState(2), bonusBState(2), 'p')).toBe(false);
  });

  it('is false when a kind only reaches 1/2', () => {
    expect(svc.bonusBJustCompleted(bonusBState(0), bonusBState(1), 'p')).toBe(false);
  });
});

describe('CellHighlightService.newPunishmentTaken', () => {
  const svc = new CellHighlightService();
  const punish = (n: number) => ({ sheetProgress: { p: { punishments: n } } }) as unknown as GameState;

  it('is true when total punishments rise', () => {
    expect(svc.newPunishmentTaken(punish(0), punish(1))).toBe(true);
  });

  it('is false when unchanged', () => {
    expect(svc.newPunishmentTaken(punish(2), punish(2))).toBe(false);
  });
});

/** State where player 'p' has crossed `crossed` (0–2) of two Bonus A box cells. */
function bonusBoxState(crossed: number): GameState {
  const cells = ['a1', 'a2'].map((id) => ({ id, tags: [{ type: CellTag.TypeEnum.BONUS_BOX }] }));
  return {
    sheetLayouts: { p: { rows: [{ id: 'r', cells }] } },
    sheetProgress: { p: { rowStates: { r: { crossedCells: ['a1', 'a2'].slice(0, crossed) } } } },
  } as unknown as GameState;
}

describe('CellHighlightService.justCrossedBonusBox', () => {
  const svc = new CellHighlightService();

  it('is true when a new Bonus A/B box is crossed', () => {
    expect(svc.justCrossedBonusBox(bonusBoxState(0), bonusBoxState(1), 'p')).toBe(true);
  });

  it('is false when the crossed-box count is unchanged', () => {
    expect(svc.justCrossedBonusBox(bonusBoxState(1), bonusBoxState(1), 'p')).toBe(false);
  });

  it('treats a missing previous state as zero crossed boxes', () => {
    expect(svc.justCrossedBonusBox(null, bonusBoxState(1), 'p')).toBe(true);
  });
});
