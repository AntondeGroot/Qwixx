import { TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import type { Mocked } from 'vitest';
import { ScoreComponent } from './score.component';
import { isScoringColourRow } from './score-rows.util';
import type { GameState, ScoreCard, SheetRow } from '../../generated/model/models';
import { GamesService, GamestatesService, PlayersService } from '../../generated/api/api';
import { environment } from '../../environments/environment';

describe('ScoreComponent', () => {
  let component: ScoreComponent;
  let playersService: Mocked<PlayersService>;

  beforeEach(() => {
    playersService = {
      leaveGame: vi.fn().mockReturnValue(of(void 0)),
    } as unknown as Mocked<PlayersService>;

    TestBed.configureTestingModule({
      imports: [ScoreComponent],
      providers: [
        { provide: GamesService, useValue: { getScores: vi.fn().mockReturnValue(of(null)) } },
        {
          provide: GamestatesService,
          useValue: { getGameState: vi.fn().mockReturnValue(of(null)) },
        },
        { provide: PlayersService, useValue: playersService },
        { provide: TranslateService, useValue: { instant: (key: string) => key } },
        { provide: Router, useValue: { navigate: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => 'session-1' }, data: {} },
            queryParamMap: of({ get: () => 'player-1' }),
          },
        },
      ],
    });

    component = TestBed.createComponent(ScoreComponent).componentInstance;
    component.sessionId = 'session-1';
    component.playerId = 'player-1';
  });

  describe('isScoringColourRow', () => {
    const row = (over: Partial<SheetRow>): SheetRow => ({ cells: [{ color: 'RED', tags: [] }], ...over }) as SheetRow;

    it('counts the four colour rows', () => {
      expect(isScoringColourRow(row({}))).toBe(true);
    });

    it('excludes the Bonus B strip and Bonus A bar so they are not score columns', () => {
      expect(isScoringColourRow(row({ bonusBStrip: true }))).toBe(false);
      expect(isScoringColourRow(row({ bonusBar: true }))).toBe(false);
      expect(isScoringColourRow(row({ bonusRow: true }))).toBe(false);
      expect(isScoringColourRow(row({ luckyRow: true }))).toBe(false);
    });
  });

  describe('the BONUS column', () => {
    const stateWith = (rows: Partial<SheetRow>[]): GameState =>
      ({
        players: [{ id: 'player-1', name: 'A' }],
        sheetLayouts: {
          'player-1': { rows: rows.map((r) => ({ cells: [{ color: 'RED', tags: [] }], ...r })) },
        },
      }) as unknown as GameState;

    const scoresWith = (bonusPoints: number): Record<string, ScoreCard> =>
      ({ 'player-1': { pointsPerColor: {}, extraPoints: 0, bonusPoints } }) as unknown as Record<string, ScoreCard>;

    const buildCols = (state: GameState, scores: Record<string, ScoreCard>): string[] =>
      component['buildScoreData'](state, scores).cols.map((c) => c.key);

    it('shows for Bonus B even at 0 points, like the colour columns', () => {
      const cols = buildCols(stateWith([{}, { bonusBStrip: true }]), scoresWith(0));

      expect(cols).toContain('BONUS');
    });

    it('stays hidden without Bonus B when nobody scored a bonus', () => {
      const cols = buildCols(stateWith([{}]), scoresWith(0));

      expect(cols).not.toContain('BONUS');
    });

    it('still shows without Bonus B once someone scores a bonus', () => {
      const cols = buildCols(stateWith([{}]), scoresWith(13));

      expect(cols).toContain('BONUS');
    });
  });

  describe('animationKey cleanup', () => {
    const ANIMATION_KEY = 'qwixx_score_shown_session-1';

    beforeEach(() => {
      sessionStorage.setItem(ANIMATION_KEY, '1');
    });

    afterEach(() => {
      sessionStorage.removeItem(ANIMATION_KEY);
    });

    it('newGame() removes the animation key so the next score screen replays the animation', () => {
      component.newGame();
      expect(sessionStorage.getItem(ANIMATION_KEY)).toBeNull();
    });

    it('SSE restart removes the animation key before navigating to the new game', () => {
      const mockEventSource = {
        close: vi.fn(),
        onmessage: null as any,
        onerror: null as any,
      };
      vi.stubGlobal(
        'EventSource',
        vi.fn().mockImplementation(function () {
          return mockEventSource;
        }),
      );

      (component as any).startRestartSse();
      // Simulate the server's initial push (game is over) so the latch is set.
      mockEventSource.onmessage({ data: JSON.stringify({ gameOver: true }) });
      // Now a restart push arrives — this should trigger cleanup and navigation.
      mockEventSource.onmessage({ data: JSON.stringify({ gameOver: false }) });

      expect(sessionStorage.getItem(ANIMATION_KEY)).toBeNull();
      vi.unstubAllGlobals();
    });
  });

  describe('leaveGame', () => {
    it('redirects to the lobby URL defined in environment, not to /settings', () => {
      let navigatedTo = '';
      const locationDescriptor = Object.getOwnPropertyDescriptor(window, 'location');
      Object.defineProperty(window, 'location', {
        value: {
          set href(url: string) {
            navigatedTo = url;
          },
        },
        writable: true,
        configurable: true,
      });

      component.leaveGame();

      expect(navigatedTo).toBe(environment.lobbyUrl);

      if (locationDescriptor) {
        Object.defineProperty(window, 'location', locationDescriptor);
      }
    });

    it('calls the leaveGame API before redirecting', () => {
      Object.defineProperty(window, 'location', {
        value: { set href(_: string) {} },
        writable: true,
        configurable: true,
      });

      component.leaveGame();

      expect(playersService.leaveGame).toHaveBeenCalledWith('session-1', 'player-1');
    });
  });
});
