import { TestBed } from '@angular/core/testing';

import { CellTag, SheetCell } from '../../generated/model/models';
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

  describe('big-points cell rendering', () => {
    it('renders SVG instead of display-value span when SECONDARY_COLOR tag is present', () => {
      const f = TestBed.createComponent(CellComponent);
      f.componentRef.setInput('cell', makeCell({
        tags: [{ type: CellTag.TypeEnum.SECONDARY_COLOR, secondaryColor: 'YELLOW' as any }],
      }));
      f.detectChanges();
      const el = f.nativeElement as HTMLElement;
      expect(el.querySelector('.cell-value')).toBeNull();
      expect(el.querySelector('.big-points-svg')).not.toBeNull();
    });

    it('applies big-points-cell class when SECONDARY_COLOR tag is present', () => {
      const f = TestBed.createComponent(CellComponent);
      f.componentRef.setInput('cell', makeCell({
        tags: [{ type: CellTag.TypeEnum.SECONDARY_COLOR, secondaryColor: 'YELLOW' as any }],
      }));
      f.detectChanges();
      const el = f.nativeElement as HTMLElement;
      expect(el.querySelector('.big-points-cell')).not.toBeNull();
    });

    it('renders display-value span and no SVG for a regular cell', () => {
      const f = TestBed.createComponent(CellComponent);
      f.componentRef.setInput('cell', makeCell());
      f.detectChanges();
      const el = f.nativeElement as HTMLElement;
      expect(el.querySelector('.cell-value')).not.toBeNull();
      expect(el.querySelector('.big-points-svg')).toBeNull();
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