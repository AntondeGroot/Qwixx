import { Component, computed, input, output } from '@angular/core';
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
  clickableCellIds           = input<Set<string>>(new Set());
  showClickableCellIds       = input<Set<string> | null>(null);
  whiteWhiteClickableCellIds = input<Set<string>>(new Set());
  pendingCellIds             = input<Set<string>>(new Set());

  lockClickable = input(false);

  cellClicked = output<string>();
  lockClicked  = output<void>();

  // Pixel x-centers of auto-cross connections to the row above / below.
  // Computed by the board and passed in so the row knows which direction to draw.
  connectorOffsetsAbove = input<number[]>([]);
  connectorOffsetsBelow = input<number[]>([]);

  regularCells         = computed(() => this.row().cells.filter(c => !c.closingEligible));
  closingEligibleCells = computed(() => this.row().cells.filter(c => c.closingEligible));

  isCrossed(cellId: string): boolean {
    return this.rowState()?.crossedCells.includes(cellId) ?? false;
  }
}