import { Component, input } from '@angular/core';
import { SheetRow } from '../../generated/model/sheetRow';
import { RowState } from '../../generated/model/rowState';
import { CellComponent } from '../cell/cell.component';

@Component({
  selector: 'app-row',
  imports: [CellComponent],
  templateUrl: './row.component.html'
})
export class RowComponent {
  row      = input.required<SheetRow>();
  rowState = input<RowState | null>(null);
  closed   = input(false);

  isCrossed(cellId: string): boolean {
    return this.rowState()?.crossedCells.includes(cellId) ?? false;
  }
}