import { Component, computed, input } from '@angular/core';
import { SheetCell } from '../../generated/model/sheetCell';
import { CellTag } from '../../generated/model/cellTag';

@Component({
  selector: 'app-cell',
  templateUrl: './cell.component.html',
  styleUrl: './cell.component.css'
})
export class CellComponent {
  cell    = input.required<SheetCell>();
  crossed = input(false);

  hasTag = computed(() => (type: CellTag.TypeEnum) =>
    this.cell().tags.some(t => t.type === type)
  );

  readonly TagEnum = CellTag.TypeEnum;
}