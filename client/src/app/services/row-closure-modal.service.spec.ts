import { TestBed } from '@angular/core/testing';
import { RowClosureModalService } from './row-closure-modal.service';
import { Color } from '../../generated/model/color';

describe('RowClosureModalService', () => {
  let service: RowClosureModalService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(RowClosureModalService);
  });

  it('starts with empty requests and no callbacks', () => {
    expect(service.requests()).toEqual([]);
    expect(service.confirmFn).toBeNull();
    expect(service.changeFn).toBeNull();
  });

  it('show() sets requests signal', () => {
    service.show(
      [{ playerName: 'Alice', rowColor: Color.RED }],
      () => {},
      () => {}
    );
    expect(service.requests()).toHaveLength(1);
    expect(service.requests()[0].playerName).toBe('Alice');
  });

  it('show() sets confirm and change callbacks', () => {
    const onConfirm = vi.fn();
    const onChange  = vi.fn();
    service.show([{ playerName: 'A', rowColor: Color.BLUE }], onConfirm, onChange);

    service.confirmFn!();
    expect(onConfirm).toHaveBeenCalledOnce();

    service.changeFn!();
    expect(onChange).toHaveBeenCalledOnce();
  });

  it('clear() empties requests and nulls callbacks', () => {
    service.show([{ playerName: 'A', rowColor: Color.GREEN }], () => {}, () => {});
    service.clear();

    expect(service.requests()).toEqual([]);
    expect(service.confirmFn).toBeNull();
    expect(service.changeFn).toBeNull();
  });

  it('replaces previous requests on a second show()', () => {
    service.show([{ playerName: 'A', rowColor: Color.RED }],   () => {}, () => {});
    service.show([{ playerName: 'B', rowColor: Color.BLUE }, { playerName: 'C', rowColor: Color.GREEN }], () => {}, () => {});
    expect(service.requests()).toHaveLength(2);
    expect(service.requests()[0].playerName).toBe('B');
  });
});
