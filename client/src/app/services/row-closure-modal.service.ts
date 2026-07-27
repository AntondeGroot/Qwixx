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
  // Role flags picking the notice's button set (never derived from a pending cross):
  //   canAct    → in the passive queue: [Make a move] + [Pass]
  //   canRevert → already ended their turn but can revert: [Undo] + [OK]
  //   neither   → observer: a single [OK]
  readonly canAct = signal(false);
  readonly canRevert = signal(false);
  confirmFn: (() => void) | null = null; // [Pass] — deliberately end the turn
  dismissFn: (() => void) | null = null; // [Make a move] / [OK] — hide the notice
  revertFn: (() => void) | null = null; // [Undo] — RESET_TURN to react again

  show(
    requests: ClosureNotification[],
    onConfirm: () => void,
    onDismiss: () => void,
    onRevert: () => void,
    canAct = false,
    canRevert = false,
  ) {
    this.requests.set(requests);
    this.canAct.set(canAct);
    this.canRevert.set(canRevert);
    this.confirmFn = onConfirm;
    this.dismissFn = onDismiss;
    this.revertFn = onRevert;
  }

  clear() {
    this.requests.set([]);
    this.canAct.set(false);
    this.canRevert.set(false);
    this.confirmFn = null;
    this.dismissFn = null;
    this.revertFn = null;
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
