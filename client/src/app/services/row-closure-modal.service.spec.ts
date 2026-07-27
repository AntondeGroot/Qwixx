import { TestBed } from '@angular/core/testing';
import { RowClosureModalService } from './row-closure-modal.service';
import { Color } from '../../generated/model/models';

describe('RowClosureModalService', () => {
  let service: RowClosureModalService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(RowClosureModalService);
  });

  it('starts with empty requests and no callbacks', () => {
    expect(service.requests()).toEqual([]);
    expect(service.confirmFn).toBeNull();
    expect(service.dismissFn).toBeNull();
    expect(service.revertFn).toBeNull();
  });

  const noop = () => {};

  it('show() sets requests signal', () => {
    service.show([{ playerName: 'Alice', rowColor: Color.RED, kind: 'closure' }], noop, noop, noop);
    expect(service.requests()).toHaveLength(1);
    expect(service.requests()[0]!.playerName).toBe('Alice');
  });

  it('show() sets confirm, dismiss and revert callbacks', () => {
    const onConfirm = vi.fn();
    const onDismiss = vi.fn();
    const onRevert = vi.fn();
    service.show([{ playerName: 'A', rowColor: Color.BLUE, kind: 'closure' }], onConfirm, onDismiss, onRevert);

    service.confirmFn!();
    expect(onConfirm).toHaveBeenCalledOnce();

    service.dismissFn!();
    expect(onDismiss).toHaveBeenCalledOnce();

    service.revertFn!();
    expect(onRevert).toHaveBeenCalledOnce();
  });

  it('clear() empties requests and nulls callbacks', () => {
    service.show([{ playerName: 'A', rowColor: Color.GREEN, kind: 'closure' }], noop, noop, noop);
    service.clear();

    expect(service.requests()).toEqual([]);
    expect(service.confirmFn).toBeNull();
    expect(service.dismissFn).toBeNull();
    expect(service.revertFn).toBeNull();
  });

  it('replaces previous requests on a second show()', () => {
    service.show([{ playerName: 'A', rowColor: Color.RED, kind: 'closure' }], noop, noop, noop);
    service.show(
      [
        { playerName: 'B', rowColor: Color.BLUE, kind: 'closure' },
        { playerName: 'C', rowColor: Color.GREEN, kind: 'closure' },
      ],
      noop,
      noop,
      noop,
    );
    expect(service.requests()).toHaveLength(2);
    expect(service.requests()[0]!.playerName).toBe('B');
  });
});
