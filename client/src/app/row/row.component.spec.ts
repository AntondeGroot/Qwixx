import { TestBed } from '@angular/core/testing';
import { SheetCell } from '../../generated/model/sheetCell';
import { SheetRow } from '../../generated/model/sheetRow';
import { RowComponent } from './row.component';

function makeCell(id: string, closingEligible = false): SheetCell {
  return { id, position: 0, displayValue: '7', color: 'RED' as any, closingEligible, tags: [] };
}

function makeRow(cells: SheetCell[]): SheetRow {
  return { id: 'r1', cells, lock: { id: 'l1', color: 'RED' as any, minCrosses: 5, closingCells: [] } };
}

describe('RowComponent', () => {
  let component: RowComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [RowComponent] }).compileComponents();
    const fixture = TestBed.createComponent(RowComponent);
    component = fixture.componentInstance;
  });

  describe('regularCells / closingEligibleCells', () => {
    it('separates regular from closing-eligible cells', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).row = () => makeRow([
          makeCell('a', false),
          makeCell('b', true),
          makeCell('c', false),
        ]);
      });
      expect(component.regularCells().map(c => c.id)).toEqual(['a', 'c']);
      expect(component.closingEligibleCells().map(c => c.id)).toEqual(['b']);
    });

    it('returns all regular when none are closing-eligible', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).row = () => makeRow([makeCell('x'), makeCell('y')]);
      });
      expect(component.regularCells()).toHaveLength(2);
      expect(component.closingEligibleCells()).toHaveLength(0);
    });
  });

  describe('isCrossed', () => {
    it('returns false when rowState is null', () => {
      expect(component.isCrossed('c1')).toBe(false);
    });

    it('returns true when cell is in crossedCells', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).rowState = () => ({ crossedCells: ['c1', 'c2'], lockCrossed: false });
      });
      expect(component.isCrossed('c1')).toBe(true);
    });

    it('returns false when cell is not in crossedCells', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).rowState = () => ({ crossedCells: ['c2'], lockCrossed: false });
      });
      expect(component.isCrossed('c1')).toBe(false);
    });
  });

  describe('input defaults', () => {
    it('closed defaults to false', () => {
      expect(component.closed()).toBe(false);
    });

    it('clickableCellIds defaults to empty set', () => {
      expect(component.clickableCellIds().size).toBe(0);
    });

    it('pendingCellIds defaults to empty set', () => {
      expect(component.pendingCellIds().size).toBe(0);
    });

    it('lockClickable defaults to false', () => {
      expect(component.lockClickable()).toBe(false);
    });
  });
});