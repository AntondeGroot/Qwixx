import { inject, Injectable, signal } from '@angular/core';
import { environment } from '../../environments/environment';
import { PlayersService } from '../../generated/api/players.service';

@Injectable({ providedIn: 'root' })
export class RoomService {
  private readonly playersService = inject(PlayersService);

  readonly roomId    = signal<string | null>(sessionStorage.getItem('qwixx_roomid'));
  readonly sessionId = signal<string | null>(null);
  readonly playerId  = signal<string | null>(null);

  setGame(sessionId: string, playerId: string, roomId: string | null) {
    this.sessionId.set(sessionId);
    this.playerId.set(playerId);
    if (roomId) {
      this.roomId.set(roomId);
      sessionStorage.setItem('qwixx_roomid', roomId);
    }
  }

  exit() {
    const sid = this.sessionId();
    const pid = this.playerId();
    const exitUrl = this.buildExitUrl();

    const navigate = () => { window.location.href = exitUrl; };

    if (sid && pid) {
      this.playersService.leaveGame(sid, pid)
        .subscribe({ next: navigate, error: navigate });
    } else {
      navigate();
    }
  }

  private buildExitUrl(): string {
    const rid = this.roomId();
    const base = environment.lobbyUrl.replace(/\/$/, '');
    return rid ? `${base}/#room=${rid}` : `${base}/`;
  }
}
