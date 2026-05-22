import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ActivatedRoute } from '@angular/router';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { of, Subject, throwError } from 'rxjs';
import type { Mocked } from 'vitest';
import { provideTranslateService, TranslateLoader, TranslationObject } from '@ngx-translate/core';
import { Observable } from 'rxjs';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { MovesService } from '../../generated/api/moves.service';
import { Color } from '../../generated/model/color';
import { GameState } from '../../generated/model/gameState';
import { MoveType } from '../../generated/model/moveType';
import { TurnPhase } from '../../generated/model/turnPhase';
import { RowClosureModalService } from '../services/row-closure-modal.service';
import { DiceSvgService } from '../dice/dice-svg.service';
import { BoardComponent } from './board.component';

const mockDiceSvgService = { getUrl: () => Promise.resolve('') };

class MockLoader implements TranslateLoader {
  getTranslation(): Observable<TranslationObject> { return of({}); }
}

const PLAYER_ID = 'player-1';
const OTHER_ID  = 'player-2';

function makeState(overrides: Partial<GameState> = {}): GameState {
  return {
    players: [{ id: PLAYER_ID, name: 'P1' }, { id: OTHER_ID, name: 'P2' }],
    sheetProgress: {
      [PLAYER_ID]: { punishments: 0, rowStates: {} },
      [OTHER_ID]:  { punishments: 0, rowStates: {} },
    },
    sheetLayouts: { [PLAYER_ID]: { rows: [] }, [OTHER_ID]: { rows: [] } },
    gameOver: false,
    version: 1,
    ...overrides,
  };
}

describe('BoardComponent — punishment / pass', () => {
  let component: BoardComponent;
  let movesService: Mocked<MovesService>;

  beforeEach(async () => {
    movesService = { makeMove: vi.fn().mockReturnValue(of({ result: 'ACCEPTED' } as any)) } as unknown as Mocked<MovesService>;

    await TestBed.configureTestingModule({
      imports: [BoardComponent],
      providers: [
        { provide: ActivatedRoute,      useValue: { snapshot: { paramMap: { get: () => '' } } } },
        { provide: GamestatesService,   useValue: { getGameState: () => of(makeState()) } },
        { provide: MovesService,        useValue: movesService },
        { provide: DiceSvgService,      useValue: mockDiceSvgService },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(BoardComponent);
    component = fixture.componentInstance;
    component.playerId.set(PLAYER_ID);
  });

  // ── canTakePunishment ──────────────────────────────────────────────────────

  describe('canTakePunishment', () => {
    it('returns false when player already has 4 punishments', () => {
      component.gameState.set(makeState({
        sheetProgress: { [PLAYER_ID]: { punishments: 4, rowStates: {} } },
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE },
      }));
      expect(component.canTakePunishment(PLAYER_ID)).toBe(false);
    });

    it('returns true for own player in ACTIVE_MOVE (give up)', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE },
      }));
      expect(component.canTakePunishment(PLAYER_ID)).toBe(true);
    });

    it('returns false for another player in ACTIVE_MOVE', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE },
      }));
      expect(component.canTakePunishment(OTHER_ID)).toBe(false);
    });

    it('returns false when it is not the player\'s active turn', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE },
      }));
      expect(component.canTakePunishment(PLAYER_ID)).toBe(false);
    });

    it('returns false for passive player in PASSIVE_MOVE (use Pass button instead)', () => {
      component.gameState.set(makeState({
        turnState: {
          activePlayerId: OTHER_ID,
          phase: TurnPhase.PASSIVE_MOVE,
          passivePlayerQueue: [PLAYER_ID],
        },
      }));
      expect(component.canTakePunishment(PLAYER_ID)).toBe(false);
    });

    it('returns true for offline mode regardless of phase', () => {
      component.gameState.set(makeState({ turnState: undefined }));
      expect(component.canTakePunishment(PLAYER_ID)).toBe(true);
    });
  });

  // ── onPunishmentClicked ────────────────────────────────────────────────────

  describe('onPunishmentClicked', () => {
    it('sends GIVE_UP when active player clicks punishment box (online)', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE },
      }));
      component.sessionId.set('s1');

      component.onPunishmentClicked(PLAYER_ID);

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID,
        expect.objectContaining({ moveType: MoveType.GIVE_UP })
      );
    });

    it('sends TAKE_PUNISHMENT for offline mode', () => {
      component.gameState.set(makeState({ turnState: undefined }));
      component.sessionId.set('s1');

      component.onPunishmentClicked(PLAYER_ID);

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID,
        expect.objectContaining({ moveType: MoveType.TAKE_PUNISHMENT })
      );
    });

    it('does nothing when punishment boxes are maxed out', () => {
      component.gameState.set(makeState({
        sheetProgress: { [PLAYER_ID]: { punishments: 4, rowStates: {} } },
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE },
      }));

      component.onPunishmentClicked(PLAYER_ID);

      expect(movesService.makeMove).not.toHaveBeenCalled();
    });

    it('does nothing when it is not the player\'s turn', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE },
      }));

      component.onPunishmentClicked(PLAYER_ID);

      expect(movesService.makeMove).not.toHaveBeenCalled();
    });
  });

  // ── canPassActive ─────────────────────────────────────────────────────────

  describe('canPassActive', () => {
    it('returns false before any cross is made', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [OTHER_ID] },
      }));
      expect(component.canPassActive()).toBe(false);
    });

    it('returns true after white+white cross', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [OTHER_ID], whiteWhiteUsed: true },
      }));
      expect(component.canPassActive()).toBe(true);
    });

    it('returns true after color die cross', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [OTHER_ID], colorDieUsed: true },
      }));
      expect(component.canPassActive()).toBe(true);
    });

    it('returns false for a different player even if they are active', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [PLAYER_ID], whiteWhiteUsed: true },
      }));
      expect(component.canPassActive()).toBe(false);
    });
  });

  // ── canPassPassive — simultaneous play ────────────────────────────────────

  describe('canPassPassive', () => {
    it('returns true for passive player in PASSIVE_MOVE', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.PASSIVE_MOVE, passivePlayerQueue: [PLAYER_ID] },
      }));
      expect(component.canPassPassive()).toBe(true);
    });

    it('returns true for passive player in ACTIVE_MOVE (simultaneous play)', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE, passivePlayerQueue: [PLAYER_ID] },
      }));
      expect(component.canPassPassive()).toBe(true);
    });

    it('returns false when player is not in passive queue during ACTIVE_MOVE', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE, passivePlayerQueue: [] },
      }));
      expect(component.canPassPassive()).toBe(false);
    });
  });

  // ── canRoll ───────────────────────────────────────────────────────────────

  describe('canRoll', () => {
    it('returns true for active player in ROLL phase', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ROLL },
      }));
      expect(component.canRoll()).toBe(true);
    });

    it('returns false for active player in ACTIVE_MOVE phase', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE },
      }));
      expect(component.canRoll()).toBe(false);
    });

    it('returns false for non-active player in ROLL phase', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ROLL },
      }));
      expect(component.canRoll()).toBe(false);
    });
  });

  // ── canGiveUp ─────────────────────────────────────────────────────────────

  describe('canGiveUp', () => {
    it('returns true for active player in ACTIVE_MOVE before any cross', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [OTHER_ID] },
      }));
      expect(component.canGiveUp()).toBe(true);
    });

    it('returns false after white+white cross (can End Turn instead)', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [OTHER_ID], whiteWhiteUsed: true },
      }));
      expect(component.canGiveUp()).toBe(false);
    });

    it('returns false for passive player', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [PLAYER_ID] },
      }));
      expect(component.canGiveUp()).toBe(false);
    });
  });

  // ── hasPendingPassiveCross ─────────────────────────────────────────────────

  describe('hasPendingPassiveCross', () => {
    it('returns false when not in passive queue', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [] },
      }));
      expect(component.hasPendingPassiveCross()).toBe(false);
    });

    it('returns false when in passive queue but no pending cross', () => {
      component.gameState.set(makeState({
        turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE,
                     passivePlayerQueue: [PLAYER_ID] },
      }));
      expect(component.hasPendingPassiveCross()).toBe(false);
    });

    it('returns true when in passive queue and has pending cross', () => {
      component.gameState.set(makeState({
        turnState: {
          activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE,
          passivePlayerQueue: [PLAYER_ID],
          pendingCrosses: { [PLAYER_ID]: ['cell-1'] },
        },
      }));
      expect(component.hasPendingPassiveCross()).toBe(true);
    });
  });

  // ── passPassive ────────────────────────────────────────────────────────────

  describe('passPassive', () => {
    it('sends PASS (not TAKE_PUNISHMENT) so the server accepts it', () => {
      component.gameState.set(makeState({
        turnState: {
          activePlayerId: OTHER_ID,
          phase: TurnPhase.PASSIVE_MOVE,
          passivePlayerQueue: [PLAYER_ID],
        },
      }));
      component.sessionId.set('s1');

      component.passPassive();

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID,
        expect.objectContaining({ moveType: MoveType.PASS })
      );
    });
  });

  // ── onConfirmRowClosure ────────────────────────────────────────────────────

  describe('onConfirmRowClosure', () => {
    it('sends PASS when the passive player has a pending cross', () => {
      component.gameState.set(makeState({
        turnState: {
          activePlayerId: OTHER_ID,
          phase: TurnPhase.PASSIVE_MOVE,
          passivePlayerQueue: [PLAYER_ID],
          pendingCrosses: { [PLAYER_ID]: ['some-cell'] },
        },
      }));
      component.sessionId.set('s1');

      component.onConfirmRowClosure();

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID,
        expect.objectContaining({ moveType: MoveType.PASS })
      );
    });
  });

  // ── passive undo during PASSIVE_MOVE ─────────────────────────────────────

  describe('passive player undo during PASSIVE_MOVE', () => {
    const PENDING_CELL = 'cell-pending';

    function stateInPassiveMove(hasPending = true): GameState {
      return makeState({
        turnState: {
          activePlayerId: OTHER_ID,
          phase: TurnPhase.PASSIVE_MOVE,
          passivePlayerQueue: [PLAYER_ID],
          ...(hasPending ? { pendingCrosses: { [PLAYER_ID]: [PENDING_CELL] } } : {}),
          currentRoll: { white1: 1, white2: 1, coloredDice: {} },
        },
      } as unknown as Partial<GameState>);
    }

    it('clicking a pending cell in PASSIVE_MOVE sends RESET_TURN', () => {
      component.gameState.set(stateInPassiveMove());
      component.sessionId.set('s1');

      component.onCellClicked('any-row', PENDING_CELL);

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID, expect.objectContaining({ moveType: MoveType.RESET_TURN })
      );
    });

    it('onChangeRowClosure with a pending cross sends UNDO_LAST_CROSS', () => {
      component.gameState.set(stateInPassiveMove());
      component.sessionId.set('s1');

      (component as any).onChangeRowClosure();

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID, expect.objectContaining({ moveType: MoveType.UNDO_LAST_CROSS })
      );
    });

    it('onChangeRowClosure without a pending cross dismisses the modal locally without a server call', () => {
      // No pending cross — "Change" means "let me pick a cell", not "reset state".
      // The modal is dismissed client-side so the player can click the board.
      component.gameState.set(stateInPassiveMove(false));
      component.sessionId.set('s1');

      (component as any).onChangeRowClosure();

      expect(movesService.makeMove).not.toHaveBeenCalled();
    });
  });

  // ── onCellClicked (passive player) ────────────────────────────────────────

  describe('onCellClicked — passive player', () => {
    const ROW_ID  = 'row-red';
    const CELL_ID = 'cell-2';

    function makeStateWithCell(overrides: Record<string, unknown> = {}): GameState {
      return makeState({
        sheetLayouts: {
          [PLAYER_ID]: {
            rows: [{
              id: ROW_ID,
              cells: [{ id: CELL_ID, position: 0, displayValue: '2', color: 'RED',
                         closingEligible: false, tags: [] }],
              lock: null,
            }],
          },
          [OTHER_ID]: { rows: [] },
        },
        turnState: {
          activePlayerId: OTHER_ID,
          phase: TurnPhase.ACTIVE_MOVE,
          passivePlayerQueue: [PLAYER_ID],
          currentRoll: { white1: 1, white2: 1, coloredDice: { RED: 1 } },
          ...overrides,
        },
      } as unknown as Partial<GameState>);
    }

    it('always sends CROSS_WHITE_WHITE for a passive player', () => {
      component.gameState.set(makeStateWithCell());
      component.sessionId.set('s1');

      component.onCellClicked(ROW_ID, CELL_ID);

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID,
        expect.objectContaining({ moveType: MoveType.CROSS_WHITE_WHITE })
      );
    });

    it('sends CROSS_WHITE_WHITE in ACTIVE_MOVE when passive has no pending cross', () => {
      component.gameState.set(makeStateWithCell({ phase: TurnPhase.ACTIVE_MOVE }));
      component.sessionId.set('s1');

      component.onCellClicked(ROW_ID, CELL_ID);

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID,
        expect.objectContaining({ moveType: MoveType.CROSS_WHITE_WHITE })
      );
    });

    it('sends CROSS_WHITE_WHITE even when active already used white+white and dice also match color die', () => {
      // Dice: white=1+1=2, RED=1 → value 2 matches BOTH white+white AND white+RED.
      // Without the passive-player guard, onCellClicked would incorrectly send CROSS_COLOR_DIE.
      component.gameState.set(makeStateWithCell({ whiteWhiteUsed: true }));
      component.sessionId.set('s1');

      component.onCellClicked(ROW_ID, CELL_ID);

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID,
        expect.objectContaining({ moveType: MoveType.CROSS_WHITE_WHITE })
      );
    });
  });

  // ── Closing-eligible cell threshold ───────────────────────────────────────

  describe('closing-eligible cell visibility', () => {
    const CLOSING_ID = 'cell-closing';
    const REGULAR_IDS = ['c0', 'c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7', 'c8', 'c9'];

    function makeClosingCellState(existingCrossCount: number): GameState {
      return makeState({
        sheetLayouts: {
          [PLAYER_ID]: {
            rows: [{
              id: 'row-red',
              cells: [
                ...REGULAR_IDS.map((id, i) => ({
                  id, position: i, displayValue: String(i + 2),
                  color: 'RED', closingEligible: false, tags: [],
                })),
                { id: CLOSING_ID, position: 10, displayValue: '12',
                  color: 'RED', closingEligible: true, tags: [] },
              ],
              lock: { id: 'lock-1', color: 'RED', minCrosses: 6, closingCells: [CLOSING_ID] },
            }],
          },
          [OTHER_ID]: { rows: [] },
        },
        sheetProgress: {
          [PLAYER_ID]: {
            punishments: 0,
            rowStates: { 'row-red': { crossedCells: REGULAR_IDS.slice(0, existingCrossCount), lockCrossed: false } },
          },
          [OTHER_ID]: { punishments: 0, rowStates: {} },
        },
        turnState: {
          activePlayerId: PLAYER_ID,
          phase: TurnPhase.ACTIVE_MOVE,
          currentRoll: { white1: 6, white2: 6, coloredDice: { RED: 6 } }, // white sum = 12
        },
      } as unknown as Partial<GameState>);
    }

    it('closing cell is NOT in clickableCellIds with fewer than minCrosses present (4+1=5 < 6)', () => {
      component.gameState.set(makeClosingCellState(4));
      expect(component.visibleClickableCellIds().has(CLOSING_ID)).toBe(false);
    });

    it('closing cell IS in clickableCellIds when exactly 5 existing crosses are present (5+1=6=minCrosses)', () => {
      component.gameState.set(makeClosingCellState(5));
      expect(component.visibleClickableCellIds().has(CLOSING_ID)).toBe(true);
    });
  });
});

// ── State-sync race-condition guards ──────────────────────────────────────────
//
// Covers three behaviours added to prevent the "game froze" scenario where a
// rapid double-click left the UI in a stale state:
//
//   1. applyState version guard  — never overwrite a newer state with an older one.
//   2. fetchState on rejection   — a rejected move still triggers a state refresh,
//                                  because a prior cancelled request may have already
//                                  changed the server state.
//   3. fetchState on cancellation — replacing an in-flight move also triggers a
//                                   refresh for the same reason.

describe('BoardComponent — state-sync race guards', () => {
  let component:    BoardComponent;
  let movesService: Mocked<MovesService>;
  let getGameState: ReturnType<typeof vi.fn>;

  const ROW_ID   = 'row-red';
  const CELL_A   = 'cell-a';
  const CELL_B   = 'cell-b';

  function makeCell(id: string) {
    return { id, position: 0, displayValue: '2', color: 'RED', closingEligible: false, tags: [] };
  }

  function stateWithPassiveQueue(): GameState {
    return makeState({
      sheetLayouts: {
        [PLAYER_ID]: { rows: [{ id: ROW_ID, cells: [makeCell(CELL_A), makeCell(CELL_B)], lock: null }] },
        [OTHER_ID]:  { rows: [] },
      },
      turnState: {
        activePlayerId: OTHER_ID,
        phase: TurnPhase.PASSIVE_MOVE,
        passivePlayerQueue: [PLAYER_ID],
        currentRoll: { white1: 1, white2: 1, coloredDice: { RED: 1 } },
      },
    } as unknown as Partial<GameState>);
  }

  beforeEach(async () => {
    getGameState = vi.fn().mockReturnValue(of(makeState()));
    movesService = {
      makeMove: vi.fn().mockReturnValue(of({ result: 'ACCEPTED' } as any)),
    } as unknown as Mocked<MovesService>;

    await TestBed.configureTestingModule({
      imports: [BoardComponent],
      providers: [
        { provide: ActivatedRoute,    useValue: { snapshot: { paramMap: { get: () => '' } } } },
        { provide: GamestatesService, useValue: { getGameState } },
        { provide: MovesService,      useValue: movesService },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(BoardComponent);
    component = fixture.componentInstance;
    component.playerId.set(PLAYER_ID);
    component.sessionId.set('s1');
  });

  // 1. Version guard ────────────────────────────────────────────────────────

  it('applyState: does not downgrade to a state with a lower version', () => {
    (component as any).applyState(makeState({ version: 5 }));
    (component as any).applyState(makeState({ version: 3 }));

    expect(component.gameState()?.version).toBe(5);
  });

  it('applyState: does apply a newer state over an older one', () => {
    (component as any).applyState(makeState({ version: 1 }));
    (component as any).applyState(makeState({ version: 4 }));

    expect(component.gameState()?.version).toBe(4);
  });

  it('applyState: applies a state with the same version (idempotent)', () => {
    (component as any).applyState(makeState({ version: 3, gameOver: false }));
    (component as any).applyState(makeState({ version: 3, gameOver: true }));

    expect(component.gameState()?.gameOver).toBe(true);
  });

  it('applyState: active player — delays state update while rolling so animation plays first', () => {
    vi.useFakeTimers();
    // Active player's turn, rolling in progress.
    const preRoll = makeState({ version: 1, turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ROLL } as any });
    component.gameState.set(preRoll);
    component.rollingDice.set(true);
    (component as any).rollStartTime = Date.now();

    const postRoll = makeState({ version: 2, turnState: { activePlayerId: PLAYER_ID, phase: TurnPhase.ACTIVE_MOVE } as any });
    (component as any).applyState(postRoll);

    // State must NOT be applied yet — active player waits for animation.
    expect(component.gameState()?.version).toBe(1);
    expect(component.rollingDice()).toBe(true);

    vi.advanceTimersByTime(3000);

    expect(component.gameState()?.version).toBe(2);
    expect(component.rollingDice()).toBe(false);
    vi.useRealTimers();
  });

  it('applyState: passive player — applies state immediately so dice area becomes visible', () => {
    vi.useFakeTimers();
    // It is another player's turn; rolling animation is running (triggered by SSE).
    const preRoll = makeState({ version: 1, turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ROLL } as any });
    component.gameState.set(preRoll);
    component.rollingDice.set(true);
    (component as any).rollStartTime = Date.now();

    const postRoll = makeState({ version: 2, turnState: { activePlayerId: OTHER_ID, phase: TurnPhase.ACTIVE_MOVE } as any });
    (component as any).applyState(postRoll);

    // State IS applied immediately so the dice area can render.
    expect(component.gameState()?.version).toBe(2);
    // Animation still running — cleared only after the roll window.
    expect(component.rollingDice()).toBe(true);

    vi.advanceTimersByTime(3000);

    expect(component.rollingDice()).toBe(false);
    vi.useRealTimers();
  });

  // 2. fetchState on rejection ──────────────────────────────────────────────

  it('refreshes state after a move is rejected by the server', () => {
    component.gameState.set(stateWithPassiveQueue());
    movesService.makeMove = vi.fn().mockReturnValue(throwError(() => ({ status: 409 })));

    getGameState.mockClear();
    component.onCellClicked(ROW_ID, CELL_A);

    expect(getGameState).toHaveBeenCalled();
  });

  // 3. fetchState on cancellation ───────────────────────────────────────────

  it('refreshes state immediately when a second click replaces an in-flight request', () => {
    component.gameState.set(stateWithPassiveQueue());

    // Use a Subject so the first request stays pending (never completes).
    const pendingMove = new Subject<unknown>();
    movesService.makeMove = vi.fn().mockReturnValue(pendingMove);

    // First click — request starts but never returns.
    component.onCellClicked(ROW_ID, CELL_A);
    getGameState.mockClear();

    // Second click — should cancel the first and immediately call fetchState.
    component.onCellClicked(ROW_ID, CELL_B);

    expect(getGameState).toHaveBeenCalledTimes(1);
  });
});

// ── Longo bonus cells — passive player ───────────────────────────────────────
//
// When the white+white sum matches one of the player's Longo bonus numbers, the
// passive player should see the leftmost uncrossed cell(s) in the row(s) with
// the fewest crosses highlighted — not just cells whose displayValue == whiteSum.

describe('BoardComponent — Longo bonus cells for passive player', () => {
  let component: BoardComponent;

  const RED_CELLS    = ['r0', 'r1', 'r2'].map((id, i) => ({
    id, position: i, displayValue: String(i + 2), color: 'RED', closingEligible: false, tags: [],
  }));
  const YELLOW_CELLS = ['y0', 'y1', 'y2'].map((id, i) => ({
    id, position: i, displayValue: String(i + 2), color: 'YELLOW', closingEligible: false, tags: [],
  }));

  function makeBonusState(opts: {
    phase?:         TurnPhase;
    bonusNums?:     number[];
    white1?:        number;
    white2?:        number;
    redCrosses?:    string[];
    yellowCrosses?: string[];
  } = {}): GameState {
    const {
      phase         = TurnPhase.PASSIVE_MOVE,
      bonusNums     = [5],
      white1        = 2,
      white2        = 3,
      redCrosses    = [],
      yellowCrosses = [],
    } = opts;

    return makeState({
      bonusNumbers: { [PLAYER_ID]: bonusNums },
      sheetLayouts: {
        [PLAYER_ID]: {
          rows: [
            { id: 'row-red',    cells: RED_CELLS,    lock: null },
            { id: 'row-yellow', cells: YELLOW_CELLS, lock: null },
          ],
        },
        [OTHER_ID]: { rows: [] },
      },
      sheetProgress: {
        [PLAYER_ID]: {
          punishments: 0,
          rowStates: {
            'row-red':    { crossedCells: redCrosses,    lockCrossed: false },
            'row-yellow': { crossedCells: yellowCrosses, lockCrossed: false },
          },
        },
        [OTHER_ID]: { punishments: 0, rowStates: {} },
      },
      turnState: {
        activePlayerId: OTHER_ID,
        phase,
        passivePlayerQueue: [PLAYER_ID],
        currentRoll: { white1, white2, coloredDice: {} },
      },
    } as unknown as Partial<GameState>);
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BoardComponent],
      providers: [
        { provide: ActivatedRoute,    useValue: { snapshot: { paramMap: { get: () => '' } } } },
        { provide: GamestatesService, useValue: { getGameState: () => of(makeState()) } },
        { provide: MovesService,      useValue: { makeMove: vi.fn().mockReturnValue(of({})) } },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(BoardComponent);
    component = fixture.componentInstance;
    component.playerId.set(PLAYER_ID);
  });

  it('passive player sees leftmost bonus cell when white sum matches bonus number', () => {
    // RED has 0 crosses (fewest), YELLOW has 1 cross → bonus targets RED only.
    component.gameState.set(makeBonusState({ yellowCrosses: ['y0'] }));
    const ids = component.clickableCellIds();
    expect(ids.has('r0')).toBe(true);   // leftmost uncrossed in RED (fewest)
    expect(ids.has('y1')).toBe(false);  // YELLOW is not at fewest
  });

  it('passive player sees bonus cells for ALL rows tied at fewest crosses', () => {
    // RED and YELLOW both have 1 cross → both offer their leftmost uncrossed cell.
    component.gameState.set(makeBonusState({ redCrosses: ['r0'], yellowCrosses: ['y0'] }));
    const ids = component.clickableCellIds();
    expect(ids.has('r1')).toBe(true);
    expect(ids.has('y1')).toBe(true);
  });

  it('passive player does not see bonus cells when white sum does not match bonus number', () => {
    // Bonus num is 5 but roll is 3+3=6.
    component.gameState.set(makeBonusState({ white1: 3, white2: 3 }));
    const ids = component.clickableCellIds();
    // r1 has displayValue '3' which equals white sum 6? No — none match, nothing offered.
    expect(ids.has('r0')).toBe(false);
    expect(ids.has('y0')).toBe(false);
  });

  it('bonus cells also appear in whiteWhiteClickableCellIds', () => {
    component.gameState.set(makeBonusState({ yellowCrosses: ['y0'] }));
    const wwIds = component.whiteWhiteClickableCellIds();
    expect(wwIds.has('r0')).toBe(true);
  });

  it('passive player in ACTIVE_MOVE phase also sees bonus cells', () => {
    // Simultaneous play: passive queue is active while active player is still moving.
    component.gameState.set(makeBonusState({ phase: TurnPhase.ACTIVE_MOVE, yellowCrosses: ['y0'] }));
    const ids = component.clickableCellIds();
    expect(ids.has('r0')).toBe(true);
  });
});

// ── Modal delegation — position:fixed / CSS transform regression ─────────────
// Kept in a separate top-level describe so its beforeEach can call
// configureTestingModule independently of the describe block above.

describe('BoardComponent — row-closure modal delegation', () => {
    let fixture: ReturnType<typeof TestBed.createComponent<BoardComponent>>;
    let component: BoardComponent;
    let modalService: RowClosureModalService;
    let movesService: Mocked<MovesService>;

    beforeEach(async () => {
      movesService = { makeMove: vi.fn().mockReturnValue(of({ result: 'ACCEPTED' } as any)) } as unknown as Mocked<MovesService>;

      // EventSource is not available in jsdom — stub it so ngOnInit → setupSse() does not throw.
      vi.stubGlobal('EventSource', Object.assign(
        vi.fn().mockImplementation(function(this: any) { this.close = vi.fn(); this.readyState = 1; }),
        { CONNECTING: 0, OPEN: 1, CLOSED: 2 }
      ));

      await TestBed.configureTestingModule({
        imports: [BoardComponent, HttpClientTestingModule],
        providers: [
          { provide: ActivatedRoute,    useValue: { snapshot: { paramMap: { get: () => '' } } } },
          { provide: GamestatesService, useValue: { getGameState: () => of(makeState()) } },
          { provide: MovesService,      useValue: movesService },
          { provide: DiceSvgService,    useValue: mockDiceSvgService },
          provideRouter([]),
          provideTranslateService({ loader: { provide: TranslateLoader, useClass: MockLoader } }),
        ],
      }).compileComponents();

      fixture      = TestBed.createComponent(BoardComponent);
      component    = fixture.componentInstance;
      modalService = TestBed.inject(RowClosureModalService);
      fixture.detectChanges();          // triggers ngOnInit (sets ids from route → '')
      component.playerId.set(PLAYER_ID); // override after ngOnInit
    });

    // Structural: the board must not render the modal in its own DOM tree.
    // If it did, the modal would be inside the CSS-transformed :host and
    // position:fixed would anchor to the rotated element, not the viewport.
    it('does not render app-row-closure-modal inside the board DOM', () => {
      const modal = fixture.nativeElement.querySelector('app-row-closure-modal');
      expect(modal).toBeNull();
    });

    // Testing the effect's output via the service is tricky in a zoneless setup.
    // Instead we verify the behaviour through the component's public methods,
    // which are exactly what the service callbacks invoke.

    it('onConfirmRowClosure sends a PASS move when there is a pending cross', () => {
      component.sessionId.set('sess1');
      // Give the player a pending cross so hasPendingPassiveCross() is true.
      component.gameState.set(makeState({
        turnState: {
          activePlayerId: OTHER_ID,
          phase: TurnPhase.PASSIVE_MOVE,
          passivePlayerQueue: [PLAYER_ID],
          pendingCrosses: { [PLAYER_ID]: ['some-cell'] },
        },
      } as unknown as Partial<GameState>));
      (component as any).onConfirmRowClosure();
      expect(movesService.makeMove).toHaveBeenCalledWith(
        'sess1', PLAYER_ID, expect.objectContaining({ moveType: MoveType.PASS }));
    });

    it('onChangeRowClosure with no pending cross dismisses the modal without a server call', () => {
      // No pending cross (default state) — modal is dismissed locally so the player
      // can interact with the board. No RESET_TURN should be sent.
      component.sessionId.set('sess1');
      (component as any).onChangeRowClosure();
      expect(movesService.makeMove).not.toHaveBeenCalled();
    });

    it('clears the service on destroy', () => {
      modalService.show([{ playerName: 'P2', rowColor: Color.BLUE }], () => {}, () => {});
      expect(modalService.requests()).toHaveLength(1);

      fixture.destroy();
      expect(modalService.requests()).toHaveLength(0);
      expect(modalService.confirmFn).toBeNull();
    });

    // ── Modal routing: who sees the notification ───────────────────────────────

    describe('notification routing via _modalSync', () => {
      function stateWithRequests(opts: {
        declarantName: string;
        activeId: string;
        passiveQueue: string[];
      }): GameState {
        return makeState({
          closureNotifications: [{ playerName: opts.declarantName, rowColor: Color.BLUE }],
          turnState: {
            activePlayerId: opts.activeId,
            phase: TurnPhase.ACTIVE_MOVE,
            passivePlayerQueue: opts.passiveQueue,
            currentRoll: { white1: 1, white2: 1, coloredDice: {} },
          },
        } as unknown as Partial<GameState>);
      }

      it('does not show the modal to the player who declared the intent', () => {
        // PLAYER_ID (name 'P1') is the declarant and also in the passive queue.
        component.gameState.set(stateWithRequests({
          declarantName: 'P1',
          activeId: OTHER_ID,
          passiveQueue: [PLAYER_ID],
        }));
        TestBed.flushEffects();
        expect(modalService.requests()).toHaveLength(0);
      });

      it('shows the modal to a passive player who is not the declarant', () => {
        // OTHER_ID (name 'P2') declared; PLAYER_ID is a passive observer.
        component.gameState.set(stateWithRequests({
          declarantName: 'P2',
          activeId: OTHER_ID,
          passiveQueue: [PLAYER_ID],
        }));
        TestBed.flushEffects();
        expect(modalService.requests()).toHaveLength(1);
        expect(modalService.requests()[0].rowColor).toBe(Color.BLUE);
      });

      it('shows the modal to the active player when a passive declares intent', () => {
        // PLAYER_ID is the active player; OTHER_ID (name 'P2') is the passive declarant.
        component.gameState.set(stateWithRequests({
          declarantName: 'P2',
          activeId: PLAYER_ID,
          passiveQueue: [OTHER_ID],
        }));
        TestBed.flushEffects();
        expect(modalService.requests()).toHaveLength(1);
        expect(modalService.requests()[0].rowColor).toBe(Color.BLUE);
      });

      it('active player does not see their own declaration in the modal', () => {
        // PLAYER_ID (name 'P1') is active and declared intent.
        component.gameState.set(stateWithRequests({
          declarantName: 'P1',
          activeId: PLAYER_ID,
          passiveQueue: [OTHER_ID],
        }));
        TestBed.flushEffects();
        expect(modalService.requests()).toHaveLength(0);
      });

      it('suppresses the notification modal while a passive has a pending lock confirmation', () => {
        // Scenario: active declared BLUE intent. Passive crossed "3" via the YES/NO modal
        // (pendingAutoLock set). The notification modal must stay suppressed so the passive
        // can see the lock cross on the board and EndTurn via the board's confirm button.
        const stateBase = stateWithRequests({ declarantName: 'P2', activeId: OTHER_ID, passiveQueue: [PLAYER_ID] });
        component.gameState.set({
          ...stateBase,
          turnState: {
            ...stateBase.turnState!,
            pendingCrosses: { [PLAYER_ID]: ['cell-3'] }, // passive has a pending cross
          },
        } as GameState);
        // Simulate pendingAutoLock being set (passive went through YES/NO modal).
        (component as any).pendingAutoLock.set({ rowId: 'row-blue', autoLock: true, cellId: 'cell-3' });
        TestBed.flushEffects();
        // Modal must be suppressed — passive should see the board (and the lock cross) instead.
        expect(modalService.requests()).toHaveLength(0);
      });

      it('re-shows the notification modal after a regular cross (no lock confirmation)', () => {
        // Passive crossed a regular (non-closing) cell after dismissing the notification.
        // The modal re-appears so they can confirm their cross.
        const stateBase = stateWithRequests({ declarantName: 'P2', activeId: OTHER_ID, passiveQueue: [PLAYER_ID] });
        component.gameState.set({
          ...stateBase,
          turnState: {
            ...stateBase.turnState!,
            pendingCrosses: { [PLAYER_ID]: ['cell-regular'] },
          },
        } as GameState);
        (component as any).suppressModal.set(true); // simulate prior OK click
        // No pendingAutoLock — regular cross, not a lock confirmation.
        (component as any).pendingAutoLock.set(null);
        TestBed.flushEffects();
        // Modal re-appears (hasPendingCross=true) so the passive can confirm their cross.
        expect(modalService.requests()).toHaveLength(1);
      });
    });
});