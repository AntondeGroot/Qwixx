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
import { BoardComponent } from './board.component';

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
    it('sends PASS so the passive player acknowledges the lock intent', () => {
      component.gameState.set(makeState({
        turnState: {
          activePlayerId: OTHER_ID,
          phase: TurnPhase.LOCK_PENDING,
          passivePlayerQueue: [PLAYER_ID],
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

  // ── passive undo during LOCK_PENDING ─────────────────────────────────────

  describe('passive player undo during LOCK_PENDING', () => {
    const PENDING_CELL = 'cell-pending';

    function stateInLockPending(hasPending = true): GameState {
      return makeState({
        turnState: {
          activePlayerId: OTHER_ID,
          phase: TurnPhase.LOCK_PENDING,
          passivePlayerQueue: [PLAYER_ID],
          ...(hasPending ? { pendingCrosses: { [PLAYER_ID]: [PENDING_CELL] } } : {}),
          currentRoll: { white1: 1, white2: 1, coloredDice: {} },
        },
      } as unknown as Partial<GameState>);
    }

    it('clicking a pending cell in LOCK_PENDING sends UNDO_LAST_CROSS, not RESET_TURN', () => {
      component.gameState.set(stateInLockPending());
      component.sessionId.set('s1');

      component.onCellClicked('any-row', PENDING_CELL);

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID, expect.objectContaining({ moveType: MoveType.UNDO_LAST_CROSS })
      );
    });

    it('clicking a pending cell in PASSIVE_MOVE still sends RESET_TURN', () => {
      component.gameState.set(makeState({
        turnState: {
          activePlayerId: OTHER_ID, phase: TurnPhase.PASSIVE_MOVE,
          passivePlayerQueue: [PLAYER_ID],
          pendingCrosses: { [PLAYER_ID]: [PENDING_CELL] },
          currentRoll: { white1: 1, white2: 1, coloredDice: {} },
        },
      } as unknown as Partial<GameState>));
      component.sessionId.set('s1');

      component.onCellClicked('any-row', PENDING_CELL);

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID, expect.objectContaining({ moveType: MoveType.RESET_TURN })
      );
    });

    it('onChangeRowClosure with a pending cross sends UNDO_LAST_CROSS', () => {
      component.gameState.set(stateInLockPending());
      component.sessionId.set('s1');

      (component as any).onChangeRowClosure();

      expect(movesService.makeMove).toHaveBeenCalledWith(
        's1', PLAYER_ID, expect.objectContaining({ moveType: MoveType.UNDO_LAST_CROSS })
      );
    });

    it('onChangeRowClosure without a pending cross dismisses the modal locally without a server call', () => {
      // No pending cross — "Change" means "let me pick a cell", not "reset state".
      // The modal is dismissed client-side so the player can click the board.
      component.gameState.set(stateInLockPending(false));
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

    it('sends CROSS_WHITE_WHITE in LOCK_PENDING when passive has no pending cross', () => {
      // Declaring lock re-invites passive players; they must be able to cross a cell.
      component.gameState.set(makeStateWithCell({ phase: TurnPhase.LOCK_PENDING }));
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
              lock: { id: 'lock-1', color: 'RED', minCrosses: 6, requiredCells: [CLOSING_ID] },
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

      await TestBed.configureTestingModule({
        imports: [BoardComponent, HttpClientTestingModule],
        providers: [
          { provide: ActivatedRoute,    useValue: { snapshot: { paramMap: { get: () => '' } } } },
          { provide: GamestatesService, useValue: { getGameState: () => of(makeState()) } },
          { provide: MovesService,      useValue: movesService },
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

    it('onConfirmRowClosure sends a PASS move', () => {
      component.sessionId.set('sess1');
      // This is the method wired to modalService.confirmFn inside the effect.
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
});