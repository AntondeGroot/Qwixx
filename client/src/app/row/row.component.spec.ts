import { TestBed } from '@angular/core/testing';
import { CellTag } from '../../generated';
import { SheetCell } from '../../generated';
import { SheetRow } from '../../generated';
import { RowComponent } from './row.component';

function makeCell(id: string, closingEligible = false): SheetCell {
  return { id, position: 0, displayValue: '7', color: 'RED' as any, closingEligible, tags: [] };
}

function makeBigPointsCells(count: number): SheetCell[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `c${i}`, position: i, displayValue: String(i + 2),
    color: 'RED' as any, closingEligible: false,
    tags: [{ type: CellTag.TypeEnum.SECONDARY_COLOR, secondaryColor: 'YELLOW' as any }],
  }));
}

function makeBonusRow(cells: SheetCell[]): SheetRow {
  return { id: 'r1', cells, lock: null as any };
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

describe('RowComponent — bonus row zone placement', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [RowComponent] }).compileComponents();
  });

  it('puts the last cell into .bonus-lock-zone for a standard bonus row (11 cells)', () => {
    const f = TestBed.createComponent(RowComponent);
    f.componentRef.setInput('row', makeBonusRow(makeBigPointsCells(11)));
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const bonusZone = el.querySelector('.bonus-lock-zone')!;
    expect(bonusZone).not.toBeNull();
    expect(bonusZone.querySelectorAll('app-cell').length).toBe(1);
    const mainCells = Array.from(el.querySelectorAll('app-cell')).filter(c => !bonusZone.contains(c));
    expect(mainCells.length).toBe(10);
  });

  it('puts the last two cells into .bonus-lock-zone for a longo bonus row (15 cells)', () => {
    const f = TestBed.createComponent(RowComponent);
    f.componentRef.setInput('row', makeBonusRow(makeBigPointsCells(15)));
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const bonusZone = el.querySelector('.bonus-lock-zone')!;
    expect(bonusZone).not.toBeNull();
    expect(bonusZone.querySelectorAll('app-cell').length).toBe(2);
    const mainCells = Array.from(el.querySelectorAll('app-cell')).filter(c => !bonusZone.contains(c));
    expect(mainCells.length).toBe(13);
  });

  it('regular row with lock has no .bonus-lock-zone', () => {
    const f = TestBed.createComponent(RowComponent);
    f.componentRef.setInput('row', makeRow([makeCell('a'), makeCell('b', true)]));
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector('.bonus-lock-zone')).toBeNull();
  });
});
