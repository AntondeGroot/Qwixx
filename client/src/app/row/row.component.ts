import { Component, computed, input, output } from '@angular/core';
import { SheetCell } from '../../generated/model/sheetCell';
import { SheetRow } from '../../generated/model/sheetRow';
import { RowState } from '../../generated/model/rowState';
import { CellComponent } from '../cell/cell.component';

@Component({
  selector: 'app-row',
  imports: [CellComponent],
  templateUrl: './row.component.html',
  styleUrl: './row.component.css'
})
export class RowComponent {
  row              = input.required<SheetRow>();
  rowState         = input<RowState | null>(null);
  closed           = input(false);
  clickableCellIds  = input<Set<string>>(new Set());
  showClickableCellIds = input<Set<string> | null>(null); // null = same as clickableCellIds
  pendingCellIds    = input<Set<string>>(new Set());

  lockClickable = input(false);

  cellClicked = output<string>();
  lockClicked  = output<void>();

  regularCells         = computed(() => this.row().cells.filter(c => !c.closingEligible));
  closingEligibleCells = computed(() => this.row().cells.filter(c => c.closingEligible));

  isCrossed(cellId: string): boolean {
    return this.rowState()?.crossedCells.includes(cellId) ?? false;
  }
}