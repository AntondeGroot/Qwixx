import { Component, EventEmitter, inject, Input, Output } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Color } from '../../generated/model/models';
import { ClosureNotification } from '../services/row-closure-modal.service';

@Component({
  selector: 'app-row-closure-modal',
  standalone: true,
  imports: [],
  templateUrl: './row-closure-modal.component.html',
  styleUrl: './row-closure-modal.component.css',
})
export class RowClosureModalComponent {
  private readonly translate = inject(TranslateService);

  @Input() requests: ClosureNotification[] = [];
  @Input() hasPendingCross = false;
  // When false (active player), the confirm button just dismisses so the player can continue their turn.
  @Input() confirmEndsRound = false;
  @Output() confirmSelection = new EventEmitter<void>();
  @Output() changeSelection = new EventEmitter<void>();

  @Input() lockConfirmRequest: { rowColor: Color } | null = null;
  @Output() lockYes = new EventEmitter<void>();
  @Output() lockNo = new EventEmitter<void>();

  t(key: string): string {
    return this.translate.instant(key);
  }

  getRowColorClass(color: Color): string {
    return `cell-${color.toLowerCase()}`;
  }

  onConfirm() {
    this.confirmSelection.emit();
  }
  onChangeSelection() {
    this.changeSelection.emit();
  }
  onLockYes() {
    this.lockYes.emit();
  }
  onLockNo() {
    this.lockNo.emit();
  }
}
