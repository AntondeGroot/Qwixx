import { TestBed } from '@angular/core/testing';
import { ExitConfirmService } from './exit-confirm.service';
import { RoomService } from './room.service';

describe('ExitConfirmService', () => {
  let service: ExitConfirmService;
  let exitSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    exitSpy = vi.fn();
    TestBed.configureTestingModule({
      providers: [{ provide: RoomService, useValue: { exit: exitSpy } }],
    });
    service = TestBed.inject(ExitConfirmService);
  });

  it('starts invisible', () => {
    expect(service.visible()).toBe(false);
  });

  it('prompt() makes the dialog visible', () => {
    void service.prompt();
    expect(service.visible()).toBe(true);
  });

  it('confirm() hides the dialog and calls roomService.exit()', () => {
    void service.prompt();
    service.confirm();
    expect(service.visible()).toBe(false);
    expect(exitSpy).toHaveBeenCalledOnce();
  });

  it('cancel() hides the dialog without calling roomService.exit()', () => {
    void service.prompt();
    service.cancel();
    expect(service.visible()).toBe(false);
    expect(exitSpy).not.toHaveBeenCalled();
  });

  it('confirm() resolves the prompt promise to true', async () => {
    const p = service.prompt();
    service.confirm();
    expect(await p).toBe(true);
  });

  it('cancel() resolves the prompt promise to false', async () => {
    const p = service.prompt();
    service.cancel();
    expect(await p).toBe(false);
  });

  it('confirm() without a prior prompt() does not throw', () => {
    expect(() => service.confirm()).not.toThrow();
  });

  it('cancel() without a prior prompt() does not throw', () => {
    expect(() => service.cancel()).not.toThrow();
  });
});
