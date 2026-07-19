import { Injectable, signal } from '@angular/core';
import { Color } from '../../generated/model/models';

/** A pending row-closure request shown to other players. Owned by the service (the closure-state
 *  authority) rather than the modal component, so the service never depends on the UI layer. */
export interface ClosureNotification {
  playerName: string;
  rowColor: Color;
}

@Injectable({ providedIn: 'root' })
export class RowClosureModalService {
  readonly requests = signal<ClosureNotification[]>([]);
  readonly hasPendingCross = signal(false);
  // false for active players (Confirm = dismiss, continue turn) vs true for passive (Confirm = EndTurn)
  readonly confirmEndsRound = signal(false);
  confirmFn: (() => void) | null = null;
  changeFn: (() => void) | null = null;

  show(
    requests: ClosureNotification[],
    onConfirm: () => void,
    onChange: () => void,
    hasPendingCross = false,
    confirmEndsRound = hasPendingCross,
  ) {
    this.requests.set(requests);
    this.hasPendingCross.set(hasPendingCross);
    this.confirmEndsRound.set(confirmEndsRound);
    this.confirmFn = onConfirm;
    this.changeFn = onChange;
  }

  clear() {
    this.requests.set([]);
    this.hasPendingCross.set(false);
    this.confirmEndsRound.set(false);
    this.confirmFn = null;
    this.changeFn = null;
  }

  // ── Self-initiated lock confirmation ──────────────────────────────────────

  readonly lockConfirmRequest = signal<{ rowColor: Color } | null>(null);
  lockConfirmYesFn: (() => void) | null = null;
  lockConfirmNoFn: (() => void) | null = null;

  showLockConfirm(rowColor: Color, onYes: () => void, onNo: () => void) {
    this.lockConfirmRequest.set({ rowColor });
    this.lockConfirmYesFn = onYes;
    this.lockConfirmNoFn = onNo;
  }

  clearLockConfirm() {
    this.lockConfirmRequest.set(null);
    this.lockConfirmYesFn = null;
    this.lockConfirmNoFn = null;
  }
}
