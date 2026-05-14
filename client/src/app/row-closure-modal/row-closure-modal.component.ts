import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { Color } from '../../generated/model/color';

export interface RowClosureRequest {
  playerName: string;
  rowColor: Color;
}

@Component({
  selector: 'app-row-closure-modal',
  standalone: true,
  imports: [CommonModule, TranslateModule],
  templateUrl: './row-closure-modal.component.html',
  styleUrl: './row-closure-modal.component.css'
})
export class RowClosureModalComponent {
  @Input() requests: RowClosureRequest[] = [];
  @Input() hasPendingCross = false;
  // When false (active player), the confirm button just dismisses so the player can continue their turn.
  @Input() confirmEndsRound = false;
  @Output() confirmSelection = new EventEmitter<void>();
  @Output() changeSelection = new EventEmitter<void>();

  @Input() lockConfirmRequest: { rowColor: Color } | null = null;
  @Output() lockYes = new EventEmitter<void>();
  @Output() lockNo  = new EventEmitter<void>();

  getRowColorClass(color: Color): string {
    return `cell-${color.toLowerCase()}`;
  }

  onConfirm() { this.confirmSelection.emit(); }
  onChangeSelection() { this.changeSelection.emit(); }
  onLockYes() { this.lockYes.emit(); }
  onLockNo()  { this.lockNo.emit(); }
}
