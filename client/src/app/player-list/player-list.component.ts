import { Component, computed, input } from '@angular/core';
import { environment } from '../../environments/environment';
import { Player } from '../../generated/model/player';
import { SheetLayout } from '../../generated/model/sheetLayout';
import { SheetProgress } from '../../generated/model/sheetProgress';
import { SheetRow } from '../../generated/model/sheetRow';
import { TurnPhase } from '../../generated/model/turnPhase';
import { TurnState } from '../../generated/model/turnState';

@Component({
  selector: 'app-player-list',
  imports: [],
  templateUrl: './player-list.component.html',
  styleUrl: './player-list.component.css'
})
export class PlayerListComponent {
  players        = input.required<Player[]>();
  myPlayerId     = input.required<string>();
  activePlayerId = input<string | null>(null);
  turnState      = input<TurnState | null>(null);
  sheetLayouts   = input<Record<string, SheetLayout>>({});
  sheetProgress  = input<Record<string, SheetProgress>>({});
  closedRows     = input<Record<string, string>>({});

  otherPlayers = computed(() =>
    this.players().filter(p => p.id !== this.myPlayerId())
  );

  isActive(pid: string): boolean {
    return pid === this.activePlayerId();
  }

  shouldShowPip(pid: string): boolean {
    const turn = this.turnState();
    if (!turn) return false;
    if (pid === turn.activePlayerId) return true;
    const phase = turn.phase;
    return phase === TurnPhase.ACTIVE_MOVE
        || phase === TurnPhase.PASSIVE_MOVE;
  }

  playerHasActed(pid: string): boolean {
    const turn = this.turnState();
    if (!turn) return false;
    if (pid === turn.activePlayerId) {
      return turn.phase !== TurnPhase.ROLL && turn.phase !== TurnPhase.ACTIVE_MOVE;
    }
    return !(turn.passivePlayerQueue ?? []).includes(pid);
  }

  rowsFor(pid: string): SheetRow[] {
    return this.sheetLayouts()[pid]?.rows ?? [];
  }

  isCrossed(pid: string, rowId: string, cellId: string): boolean {
    return this.sheetProgress()[pid]?.rowStates?.[rowId]?.crossedCells?.includes(cellId) ?? false;
  }

  isRowClosed(rowId: string): boolean {
    return rowId in this.closedRows();
  }

  punishmentsFor(pid: string): number {
    return this.sheetProgress()[pid]?.punishments ?? 0;
  }

  profilePicUrl(index: string): string {
    return `${environment.lobbyUrl.replace(/\/$/, '')}/profile-pic/${index}`;
  }

  initials(name: string): string {
    return name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
  }
}