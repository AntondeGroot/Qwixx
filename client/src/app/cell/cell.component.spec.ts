import { TestBed } from '@angular/core/testing';
import { CellTag } from '../../generated/model/cellTag';
import { SheetCell } from '../../generated/model/sheetCell';
import { CellComponent } from './cell.component';

function makeCell(overrides: Partial<SheetCell> = {}): SheetCell {
  return {
    id: 'c1', position: 0, displayValue: '7',
    color: 'RED' as any, closingEligible: false, tags: [],
    ...overrides,
  };
}

describe('CellComponent', () => {
  let component: CellComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [CellComponent] }).compileComponents();
    const fixture = TestBed.createComponent(CellComponent);
    component = fixture.componentInstance;
    TestBed.runInInjectionContext(() => {
      (component as any).cell = () => makeCell();
    });
  });

  describe('hasTag', () => {
    it('returns false when no tags', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).cell = () => makeCell({ tags: [] });
      });
      expect(component.hasTag()(CellTag.TypeEnum.EXTRA_BUCKET)).toBe(false);
    });

    it('returns true when tag is present', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).cell = () => makeCell({
          tags: [{ type: CellTag.TypeEnum.EXTRA_BUCKET }],
        });
      });
      expect(component.hasTag()(CellTag.TypeEnum.EXTRA_BUCKET)).toBe(true);
    });

    it('returns false for a different tag type', () => {
      TestBed.runInInjectionContext(() => {
        (component as any).cell = () => makeCell({
          tags: [{ type: CellTag.TypeEnum.DOUBLE_CROSS }],
        });
      });
      expect(component.hasTag()(CellTag.TypeEnum.EXTRA_BUCKET)).toBe(false);
    });
  });

  describe('inputs default values', () => {
    it('crossed defaults to false', () => {
      expect(component.crossed()).toBe(false);
    });

    it('pending defaults to false', () => {
      expect(component.pending()).toBe(false);
    });

    it('clickable defaults to false', () => {
      expect(component.clickable()).toBe(false);
    });
  });
});