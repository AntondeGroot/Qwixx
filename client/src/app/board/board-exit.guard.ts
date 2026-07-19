import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';
import { ExitConfirmService } from '../services/exit-confirm.service';
import { RoomService } from '../services/room.service';

export const boardExitGuard: CanDeactivateFn<unknown> = () => {
  if (!inject(RoomService).roomId()) return Promise.resolve(true);
  // Always resolves false: either the user cancels (stay on game) or confirms
  // (roomService.exit() inside ExitConfirmService does the full-page redirect).
  return inject(ExitConfirmService)
    .prompt()
    .then(() => false);
};
