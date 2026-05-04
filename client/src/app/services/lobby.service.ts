import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BASE_PATH } from '../../generated/variables';

export interface LobbyState {
  players:         { id: string; name: string }[];
  proposedOptions: { [key: string]: any };
}

@Injectable({ providedIn: 'root' })
export class LobbyService {
  private http     = inject(HttpClient);
  private basePath = inject(BASE_PATH, { optional: true }) ?? '';

  private url(sessionId: string): string {
    return `${this.basePath}/games/${sessionId}/lobby`;
  }

  get(sessionId: string): Observable<LobbyState> {
    return this.http.get<LobbyState>(this.url(sessionId));
  }

  update(sessionId: string, gameOptions: { [key: string]: any }): Observable<void> {
    return this.http.put<void>(this.url(sessionId), { gameOptions });
  }
}
