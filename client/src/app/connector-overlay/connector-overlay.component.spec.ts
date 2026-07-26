import { TestBed } from '@angular/core/testing';
import { CellTag, SheetLayout } from '../../generated/model/models';
import { ConnectorOverlayComponent } from './connector-overlay.component';

const auto = (target: string) => ({ type: CellTag.TypeEnum.AUTO_CROSS, target });

describe('ConnectorOverlayComponent', () => {
  it('measures a one-way link from the upper cell bottom-edge to the lower cell top-edge (into the gap)', () => {
    const layout = {
      rows: [
        { id: 'r0', cells: [{ id: 'src', position: 3, color: 'RED', tags: [auto('tgt')] }] },
        { id: 'r1', cells: [{ id: 'tgt', position: 3, color: 'YELLOW', tags: [] }] },
      ],
    } as unknown as SheetLayout;

    const fixture = TestBed.createComponent(ConnectorOverlayComponent);
    fixture.componentRef.setInput('layout', layout);
    const component = fixture.componentInstance;

    // Mock the parent sheet and the cell rects the overlay measures against.
    const sheet = {} as HTMLElement;
    const cell = (id: string, offsetLeft: number, offsetTop: number) =>
      ({
        offsetLeft,
        offsetTop,
        offsetWidth: 44,
        offsetHeight: 44,
        offsetParent: sheet,
        getAttribute: () => id,
      }) as unknown as HTMLElement;
    const cells = [cell('src', 60, 40), cell('tgt', 60, 100)];
    (sheet as unknown as { querySelectorAll: () => HTMLElement[] }).querySelectorAll = () => cells;
    const host = (component as unknown as { host: { nativeElement: HTMLElement } }).host.nativeElement;
    Object.defineProperty(host, 'parentElement', { value: sheet, configurable: true });

    (component as unknown as { measure: () => void }).measure();

    // src centre (82,62) → bottom edge 84, +4 into gap → 88; tgt centre (82,122) → top edge 100, −4 → 96.
    expect(component.measured()).toEqual([{ x1: 82, y1: 88, x2: 82, y2: 96, oneWay: true }]);
    expect(host.getAttribute('data-measured')).toBe('true');
  });
});
