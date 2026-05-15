import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import type { Mocked } from 'vitest';
import { ScoreComponent } from './score.component';
import { GamesService } from '../../generated/api/games.service';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { PlayersService } from '../../generated/api/players.service';
import { environment } from '../../environments/environment';

describe('ScoreComponent', () => {
  let component: ScoreComponent;
  let playersService: Mocked<PlayersService>;

  beforeEach(() => {
    playersService = { leaveGame: vi.fn().mockReturnValue(of(void 0)) } as unknown as Mocked<PlayersService>;

    TestBed.configureTestingModule({
      imports: [ScoreComponent],
      providers: [
        { provide: GamesService,      useValue: { getScores:     vi.fn().mockReturnValue(of(null)) } },
        { provide: GamestatesService, useValue: { getGameState:  vi.fn().mockReturnValue(of(null)) } },
        { provide: PlayersService,    useValue: playersService },
        { provide: Router,            useValue: { navigate: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { paramMap: { get: () => 'session-1' } },
            queryParamMap: of({ get: () => 'player-1' }),
          }
        },
      ]
    });

    component = TestBed.createComponent(ScoreComponent).componentInstance;
    component.sessionId = 'session-1';
    component.playerId  = 'player-1';
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
        onerror:   null as any,
      };
      vi.stubGlobal('EventSource', vi.fn().mockImplementation(function() { return mockEventSource; }));

      (component as any).startRestartSse();
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
        value: { set href(url: string) { navigatedTo = url; } },
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
