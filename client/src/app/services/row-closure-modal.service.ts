import { Injectable, signal } from '@angular/core';
import { RowClosureRequest } from '../row-closure-modal/row-closure-modal.component';

@Injectable({ providedIn: 'root' })
export class RowClosureModalService {
  readonly requests = signal<RowClosureRequest[]>([]);
  confirmFn: (() => void) | null = null;
  changeFn:  (() => void) | null = null;

  show(requests: RowClosureRequest[], onConfirm: () => void, onChange: () => void) {
    this.requests.set(requests);
    this.confirmFn = onConfirm;
    this.changeFn  = onChange;
  }

  clear() {
    this.requests.set([]);
    this.confirmFn = null;
    this.changeFn  = null;
  }
}
