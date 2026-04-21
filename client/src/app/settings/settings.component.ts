import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { GamesService } from '../../generated/api/games.service';
import { PlayersService } from '../../generated/api/players.service';
import { GameOption } from '../../generated/model/gameOption';
import { SheetLayout } from '../../generated/model/sheetLayout';
import { RowComponent } from '../row/row.component';

@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule, RowComponent],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css'
})
export class SettingsComponent implements OnInit, OnDestroy {
  private gamesService   = inject(GamesService);
  private playersService = inject(PlayersService);
  private router         = inject(Router);
  private fb             = inject(FormBuilder);
  private previewSub?: Subscription;

  gameOptions   = signal<GameOption[]>([]);
  previewLayout = signal<SheetLayout | null>(null);
  error         = signal<string | null>(null);
  loading       = signal(false);

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
      this.fetchPreview();

      this.form.valueChanges.subscribe(() => this.fetchPreview());
    });
  }

  ngOnDestroy() {
    this.previewSub?.unsubscribe();
  }

  private fetchPreview() {
    this.previewSub?.unsubscribe();
    const { playerName, ...optionValues } = this.form.value;
    const gameOptions: Record<string, unknown> = {};
    for (const opt of this.gameOptions()) {
      gameOptions[opt.key] = optionValues[opt.key];
    }
    this.previewSub = this.gamesService.previewLayout(gameOptions).subscribe({
      next: layout => this.previewLayout.set(layout),
      error: () => this.previewLayout.set(null)
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