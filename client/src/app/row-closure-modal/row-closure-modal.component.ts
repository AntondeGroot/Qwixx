import { Component, EventEmitter, inject, Input, Output } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Color } from '../../generated/model/models';
import { NoticeRequest } from '../services/row-closure-modal.service';

@Component({
  selector: 'app-row-closure-modal',
  standalone: true,
  imports: [],
  templateUrl: './row-closure-modal.component.html',
  styleUrl: './row-closure-modal.component.css',
})
export class RowClosureModalComponent {
  private readonly translate = inject(TranslateService);

  @Input() requests: NoticeRequest[] = [];
  // Button set: canAct → [Make a move] + [Pass]; else canRevert → [Undo] + [OK]; else single [OK].
  @Input() canAct = false;
  @Input() canRevert = false;
  @Output() confirmSelection = new EventEmitter<void>(); // [Pass]
  @Output() dismissSelection = new EventEmitter<void>(); // [Make a move] / [OK]
  @Output() revertSelection = new EventEmitter<void>(); // [Undo]

  @Input() lockConfirmRequest: { rowColor: Color } | null = null;
  @Output() lockYes = new EventEmitter<void>();
  @Output() lockNo = new EventEmitter<void>();

  t(key: string, params?: object): string {
    return this.translate.instant(key, params);
  }

  getRowColorClass(color: Color): string {
    return `cell-${color.toLowerCase()}`;
  }

  onConfirm() {
    this.confirmSelection.emit();
  }
  onDismiss() {
    this.dismissSelection.emit();
  }
  onRevert() {
    this.revertSelection.emit();
  }
  onLockYes() {
    this.lockYes.emit();
  }
  onLockNo() {
    this.lockNo.emit();
  }
}
