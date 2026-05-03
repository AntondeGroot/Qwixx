import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import type { Mocked } from 'vitest';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { MovesService } from '../../generated/api/moves.service';
import { GameState } from '../../generated/model/gameState';
import { MoveType } from '../../generated/model/moveType';
import { TurnPhase } from '../../generated/model/turnPhase';
import { BoardComponent } from './board.component';

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
});