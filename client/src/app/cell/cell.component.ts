import { Component, input } from '@angular/core';
import { SheetCell } from '../../generated/model/sheetCell';

@Component({
  selector: 'app-cell',
  templateUrl: './cell.component.html'
})
export class CellComponent {
  cell    = input.required<SheetCell>();
  crossed = input(false);
}