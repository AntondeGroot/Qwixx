import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { GameState } from '../../generated/model/gameState';
import { SheetLayout } from '../../generated/model/sheetLayout';
import { RowState } from '../../generated/model/rowState';
import { RowComponent } from '../row/row.component';

@Component({
  selector: 'app-board',
  imports: [RouterLink, RowComponent],
  templateUrl: './board.component.html'
})
export class BoardComponent implements OnInit {
  private route            = inject(ActivatedRoute);
  private gameStatesService = inject(GamestatesService);

  sessionId  = signal('');
  playerId   = signal('');
  gameState  = signal<GameState | null>(null);
  error      = signal<string | null>(null);

  ngOnInit() {
    const sid = this.route.snapshot.paramMap.get('sessionId') ?? '';
    const pid = this.route.snapshot.paramMap.get('playerId') ?? '';
    this.sessionId.set(sid);
    this.playerId.set(pid);

    this.gameStatesService.getGameState(sid).subscribe({
      next: (state: GameState) => this.gameState.set(state),
      error: () => this.error.set('Could not load game state.')
    });
  }

  layoutFor(playerId: string): SheetLayout | null {
    return this.gameState()?.sheetLayouts?.[playerId] ?? null;
  }

  rowStateFor(playerId: string, rowId: string): RowState | null {
    return this.gameState()?.sheetProgress?.[playerId]?.rowStates?.[rowId] ?? null;
  }

  isRowClosed(rowId: string): boolean {
    return rowId in (this.gameState()?.closedRows ?? {});
  }

  playerName(playerId: string): string {
    return this.gameState()?.players.find(p => p.id === playerId)?.name ?? playerId;
  }
}