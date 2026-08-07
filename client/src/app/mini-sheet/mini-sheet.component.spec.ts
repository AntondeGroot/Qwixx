import { TestBed } from '@angular/core/testing';
import { MiniSheetComponent } from './mini-sheet.component';

describe('MiniSheetComponent', () => {
  let component: MiniSheetComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [MiniSheetComponent] }).compileComponents();
    const fixture = TestBed.createComponent(MiniSheetComponent);
    component = fixture.componentInstance;
    TestBed.runInInjectionContext(() => {
      (component as any).rows = () => [];
    });
  });

  describe('isRowClosed', () => {
    it('returns true when row is in closedRows', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).closedRows = () => ({ 'row-1': 'p1' });
      });
      expect(component.isRowClosed('row-1')).toBe(true);
    });

    it('returns false when row is not closed', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).closedRows = () => ({});
      });
      expect(component.isRowClosed('row-1')).toBe(false);
    });
  });

  describe('isCrossed', () => {
    it('returns true when cell is in crossedCells', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).progress = () => ({
          rowStates: { r1: { crossedCells: ['c1'], lockCrossed: false } },
          punishments: 0,
        });
      });
      expect(component.isCrossed('r1', 'c1')).toBe(true);
    });

    it('returns false when cell is not crossed', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).progress = () => ({
          rowStates: { r1: { crossedCells: [], lockCrossed: false } },
          punishments: 0,
        });
      });
      expect(component.isCrossed('r1', 'c1')).toBe(false);
    });

    it('returns false when there is no progress for the sheet', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).progress = () => null;
      });
      expect(component.isCrossed('r1', 'c1')).toBe(false);
    });
  });

  describe('isLockCrossed', () => {
    it('returns true once the row lock has been crossed', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).progress = () => ({
          rowStates: { r1: { crossedCells: [], lockCrossed: true } },
          punishments: 0,
        });
      });
      expect(component.isLockCrossed('r1')).toBe(true);
    });

    it('returns false when there is no progress for the sheet', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).progress = () => null;
      });
      expect(component.isLockCrossed('r1')).toBe(false);
    });
  });
});
