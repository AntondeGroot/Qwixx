import { Component, DestroyRef, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, EMPTY, interval, Subscription } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { GamesService } from '../../generated/api/games.service';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { PlayersService } from '../../generated/api/players.service';
import { GameOption } from '../../generated/model/gameOption';
import { SheetLayout } from '../../generated/model/sheetLayout';
import { RowComponent } from '../row/row.component';
import { LobbyService } from '../services/lobby.service';

@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule, RowComponent, TranslateModule],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css'
})
export class SettingsComponent implements OnInit, OnDestroy {
  private gamesService      = inject(GamesService);
  private gameStatesService = inject(GamestatesService);
  private playersService    = inject(PlayersService);
  private lobbyService      = inject(LobbyService);
  private router            = inject(Router);
  private route             = inject(ActivatedRoute);
  private fb                = inject(FormBuilder);
  private translate         = inject(TranslateService);
  private destroyRef        = inject(DestroyRef);
  private previewSub?: Subscription;

  // Present when navigating here from the score screen after a game ends.
  // Both are required for restart mode; absent means standalone (offline) mode.
  sessionId = signal<string | null>(null);
  playerId  = signal<string | null>(null);

  get isRestartMode(): boolean {
    return !!this.sessionId() && !!this.playerId();
  }

  gameOptions    = signal<GameOption[]>([]);
  lobbyPlayers   = signal<{id:string,name:string}[]>([]);
  previewLayout  = signal<SheetLayout | null>(null);
  error          = signal<string | null>(null);
  loading        = signal(false);

  // Suppress lobby → form updates while the player is actively editing
  private suppressLobbySync = false;

  form!: FormGroup;

  readonly TypeEnum = GameOption.TypeEnum;

  ngOnInit() {
    // Query params are authoritative; fall back to sessionStorage written by newGame().
    this.sessionId.set(
      this.route.snapshot.queryParamMap.get('sessionId') ||
      sessionStorage.getItem('qwixx_lobby_sid') ||
      null
    );
    this.playerId.set(
      this.route.snapshot.queryParamMap.get('playerId') ||
      sessionStorage.getItem('qwixx_lobby_pid') ||
      null
    );

    this.form = this.fb.group({});

    this.gamesService.getGameOptions().subscribe(opts => {
      for (const opt of opts) {
        this.form.addControl(opt.key, this.fb.control(
          opt.type === GameOption.TypeEnum.BOOLEAN
            ? opt.defaultValue === 'true'
            : opt.defaultValue
        ));
      }
      this.gameOptions.set(opts);
      this.fetchPreview();

      if (this.isRestartMode) {
        this.startLobbySync();
        this.startGameStartPoll();

        // Push local form changes to the lobby so other players see them.
        this.form.valueChanges.pipe(
          debounceTime(400),
          distinctUntilChanged((a, b) => JSON.stringify(a) === JSON.stringify(b)),
          takeUntilDestroyed(this.destroyRef),
        ).subscribe(() => {
          if (!this.suppressLobbySync) {
            this.lobbyService.update(this.sessionId()!, this.buildGameOptions())
              .subscribe({ error: () => {} });
          }
          this.fetchPreview();
        });
      } else {
        this.form.valueChanges.subscribe(() => this.fetchPreview());
      }
    });
  }

  ngOnDestroy() {
    this.previewSub?.unsubscribe();
  }

  // Poll the lobby every 2 s to pick up option changes from other players.
  private startLobbySync(): void {
    interval(2000).pipe(
      takeUntilDestroyed(this.destroyRef),
      switchMap(() =>
        this.lobbyService.get(this.sessionId()!).pipe(catchError(() => EMPTY))
      ),
    ).subscribe(lobby => {
      this.lobbyPlayers.set(lobby.players);
      this.applyLobbyOptions(lobby.proposedOptions);
    });
  }

  // Poll game state every 2 s; when another player starts the game navigate to the board.
  private startGameStartPoll(): void {
    interval(2000).pipe(
      takeUntilDestroyed(this.destroyRef),
      switchMap(() =>
        this.gameStatesService.getGameState(this.sessionId()!).pipe(catchError(() => EMPTY))
      ),
    ).subscribe(state => {
      if (!state.gameOver) {
        this.router.navigate(['/game', this.sessionId(), this.playerId()]);
      }
    });
  }

  // Update the form with lobby options without triggering an outgoing PUT.
  private applyLobbyOptions(opts: Record<string, any>): void {
    if (!opts || Object.keys(opts).length === 0) return;
    this.suppressLobbySync = true;
    for (const [key, value] of Object.entries(opts)) {
      const ctrl = this.form.get(key);
      if (ctrl && ctrl.value !== value) ctrl.setValue(value, { emitEvent: false });
    }
    this.suppressLobbySync = false;
    this.fetchPreview();
  }

  private buildGameOptions(): Record<string, unknown> {
    const values = this.form.value;
    const opts: Record<string, unknown> = {};
    for (const opt of this.gameOptions()) {
      opts[opt.key] = values[opt.key];
    }
    return opts;
  }

  private fetchPreview() {
    this.previewSub?.unsubscribe();
    this.previewSub = this.gamesService.previewLayout(this.buildGameOptions()).subscribe({
      next:  layout => this.previewLayout.set(layout),
      error: ()     => this.previewLayout.set(null),
    });
  }

  startGame() {
    if (this.form.invalid) return;
    this.error.set(null);
    this.loading.set(true);

    const gameOptions = this.buildGameOptions();

    if (this.isRestartMode) {
      // Push final options to lobby so all pollers see them, then restart.
      this.lobbyService.update(this.sessionId()!, gameOptions).subscribe(() => {
        this.gamesService.restartGame(this.sessionId()!, { gameOptions }).subscribe({
          next: () => {
            this.loading.set(false);
            this.router.navigate(['/game', this.sessionId(), this.playerId()]);
          },
          error: e => this.handleError(e),
        });
      });
    } else {
      // Standalone / offline mode: create a fresh single-player game.
      this.gamesService.createNewGame({ roomName: 'Offline', maxPlayers: 1, gameOptions })
        .subscribe({
          next: res => {
            const sessionId = res.sessionId!;
            this.playersService.addPlayerToGame(sessionId, { id: this.playerId()!, name: 'Offline' })
              .subscribe({
                next: joined => {
                  this.gamesService.startGame(sessionId).subscribe({
                    next: () => {
                      this.loading.set(false);
                      this.router.navigate(['/game', sessionId, joined.playerId]);
                    },
                    error: e => this.handleError(e),
                  });
                },
                error: e => this.handleError(e),
              });
          },
          error: e => this.handleError(e),
        });
    }
  }

  private handleError(err: unknown) {
    this.loading.set(false);
    this.error.set('Could not start game. Is the server running?');
    console.error(err);
  }

  getGameOptionLabel(key: string): string {
    return this.translate.instant(`gameOption.${key}`);
  }
}
