import { inject, Injectable, signal } from '@angular/core';
import { RoomService } from './room.service';

@Injectable({ providedIn: 'root' })
export class ExitConfirmService {
  private readonly roomService = inject(RoomService);

  readonly visible = signal(false);
  private _resolve: ((v: boolean) => void) | null = null;

  /** Shows the dialog. Resolves when the user dismisses it (value is ignored by callers). */
  prompt(): Promise<boolean> {
    this.visible.set(true);
    return new Promise<boolean>((resolve) => {
      this._resolve = resolve;
    });
  }

  confirm(): void {
    this.visible.set(false);
    this._resolve?.(true);
    this._resolve = null;
    this.roomService.exit();
  }

  cancel(): void {
    this.visible.set(false);
    this._resolve?.(false);
    this._resolve = null;
  }
}
