import { Component, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { GamesService } from '../../generated/api/games.service';
import { PlayersService } from '../../generated/api/players.service';
import { GameOption } from '../../generated/model/gameOption';

@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  templateUrl: './settings.component.html'
})
export class SettingsComponent implements OnInit {
  private gamesService   = inject(GamesService);
  private playersService = inject(PlayersService);
  private router         = inject(Router);
  private fb             = inject(FormBuilder);

  gameOptions = signal<GameOption[]>([]);
  error       = signal<string | null>(null);
  loading     = signal(false);

  form!: FormGroup;

  readonly TypeEnum = GameOption.TypeEnum;

  ngOnInit() {
    this.form = this.fb.group({
      playerName: ['', Validators.required]
    });

    this.gamesService.getGameOptions().subscribe(opts => {
      for (const opt of opts) {
        this.form.addControl(opt.key, this.fb.control(
          opt.type === GameOption.TypeEnum.BOOLEAN
            ? opt.defaultValue === 'true'
            : opt.defaultValue
        ));
      }
      this.gameOptions.set(opts);
    });
  }

  startGame() {
    if (this.form.invalid) return;
    this.error.set(null);
    this.loading.set(true);

    const { playerName, ...optionValues } = this.form.value;
    const gameOptions: Record<string, unknown> = {};
    for (const opt of this.gameOptions()) {
      gameOptions[opt.key] = optionValues[opt.key];
    }

    this.gamesService.createNewGame({ roomName: playerName, maxPlayers: 6, gameOptions })
      .subscribe({
        next: res => {
          const sessionId = res.sessionId!;
          this.playersService.addPlayerToGame(sessionId, { name: playerName })
            .subscribe({
              next: joined => {
                this.gamesService.startGame(sessionId).subscribe({
                  next: () => {
                    this.loading.set(false);
                    this.router.navigate(['/game', sessionId, joined.playerId]);
                  },
                  error: e => this.handleError(e)
                });
              },
              error: e => this.handleError(e)
            });
        },
        error: e => this.handleError(e)
      });
  }

  private handleError(err: unknown) {
    this.loading.set(false);
    this.error.set('Could not start game. Is the server running?');
    console.error(err);
  }
}