import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideTranslateService, TranslateLoader, TranslationObject } from '@ngx-translate/core';
import { Observable, of } from 'rxjs';
import { Color } from '../generated/model/models';
import { RowClosureModalService } from './services/row-closure-modal.service';
import { provideRouter, Router } from '@angular/router';
import { RoomService } from './services/room.service';
import { ExitConfirmService } from './services/exit-confirm.service';

class MockTranslateLoader implements TranslateLoader {
  getTranslation(_lang: string): Observable<TranslationObject> {
    return of({});
  }
}

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClientTesting(),
        provideTranslateService({
          loader: {
            provide: TranslateLoader,
            useClass: MockTranslateLoader,
          },
        }),
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  // ── Modal placement — position:fixed / CSS transform regression ────────────
  //
  // The row-closure modal must live at the app-root level, NOT inside app-board.
  // Inside app-board the :host has transform:rotate(90deg) in portrait mode,
  // which makes position:fixed anchor to the rotated element instead of the
  // real viewport — the modal ends up off-screen or clipped on mobile.

  it('renders app-row-closure-modal directly inside the root element', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    // The modal must be a direct descendant of the app root, never inside app-board.
    const modal = fixture.nativeElement.querySelector('app-row-closure-modal');
    expect(modal).toBeTruthy();
  });

  it('modal is NOT nested inside app-board', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const board = fixture.nativeElement.querySelector('app-board');
    // If the board is not rendered (no active route in this test), the modal is
    // still at the root — absence of app-board means the modal can't be inside it.
    if (board) {
      const nestedModal = board.querySelector('app-row-closure-modal');
      expect(nestedModal).toBeNull();
    }
  });

  it('shows modal content when RowClosureModalService has requests', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const service = TestBed.inject(RowClosureModalService);
    service.show(
      [{ playerName: 'Alice', rowColor: Color.RED }],
      () => {},
      () => {},
      () => {},
    );
    fixture.detectChanges();

    const overlay = fixture.nativeElement.querySelector('.modal-overlay');
    expect(overlay).toBeTruthy();
    expect(overlay.textContent).toContain('Alice');
  });

  it('hides modal when RowClosureModalService is cleared', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const service = TestBed.inject(RowClosureModalService);
    service.show(
      [{ playerName: 'Bob', rowColor: Color.BLUE }],
      () => {},
      () => {},
      () => {},
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.modal-overlay')).toBeTruthy();

    service.clear();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.modal-overlay')).toBeFalsy();
  });
});

describe('App — back-button guard', () => {
  let pushStateSpy: ReturnType<typeof vi.spyOn>;

  const setup = async (url: string, roomId: string | null) => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClientTesting(),
        provideTranslateService({
          loader: { provide: TranslateLoader, useClass: MockTranslateLoader },
        }),
        provideRouter([]),
        {
          provide: RoomService,
          useValue: { roomId: () => roomId, exit: vi.fn(), setGame: vi.fn() },
        },
      ],
    }).compileComponents();

    const router = TestBed.inject(Router);
    vi.spyOn(router, 'url', 'get').mockReturnValue(url);
    pushStateSpy = vi.spyOn(history, 'pushState').mockImplementation(() => {});

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return fixture;
  };

  afterEach(() => vi.restoreAllMocks());

  it('pushes a phantom history entry when entering a game route with an active room', async () => {
    await setup('/game/session-1/player-1', 'room-1');
    expect(pushStateSpy).toHaveBeenCalledWith({ gameGuard: true }, '');
  });

  it('does not push a phantom when there is no active room', async () => {
    await setup('/game/session-1/player-1', null);
    expect(pushStateSpy).not.toHaveBeenCalledWith({ gameGuard: true }, '');
  });

  it('does not push a phantom on non-game routes', async () => {
    await setup('/settings', 'room-1');
    expect(pushStateSpy).not.toHaveBeenCalledWith({ gameGuard: true }, '');
  });

  it('shows the exit-confirm dialog when the back button is pressed', async () => {
    await setup('/game/session-1/player-1', 'room-1');
    const exitConfirm = TestBed.inject(ExitConfirmService);

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));

    expect(exitConfirm.visible()).toBe(true);
  });

  it('re-pushes the phantom after a back press so the next press is also intercepted', async () => {
    await setup('/game/session-1/player-1', 'room-1');
    pushStateSpy.mockClear();

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));

    expect(pushStateSpy).toHaveBeenCalledWith({ gameGuard: true }, '');
  });

  it('ignores a popstate event that carries the gameGuard flag', async () => {
    await setup('/game/session-1/player-1', 'room-1');
    const exitConfirm = TestBed.inject(ExitConfirmService);

    window.dispatchEvent(new PopStateEvent('popstate', { state: { gameGuard: true } }));

    expect(exitConfirm.visible()).toBe(false);
  });

  it('ignores a back press when not on a game route', async () => {
    await setup('/settings', 'room-1');
    const exitConfirm = TestBed.inject(ExitConfirmService);

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));

    expect(exitConfirm.visible()).toBe(false);
  });

  it('ignores a back press when there is no active room', async () => {
    await setup('/game/session-1/player-1', null);
    const exitConfirm = TestBed.inject(ExitConfirmService);

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));

    expect(exitConfirm.visible()).toBe(false);
  });

  it('does not re-open the dialog on a second back press while dialog is already visible', async () => {
    await setup('/game/session-1/player-1', 'room-1');
    const exitConfirm = TestBed.inject(ExitConfirmService);

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
    expect(exitConfirm.visible()).toBe(true);

    // Capture the original resolve so we can verify it is NOT replaced.
    const resolveBefore = (exitConfirm as any)._resolve;

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));

    expect(exitConfirm.visible()).toBe(true);
    expect((exitConfirm as any)._resolve).toBe(resolveBefore);
  });

  it('still re-pushes the phantom on a second back press while dialog is open', async () => {
    await setup('/game/session-1/player-1', 'room-1');
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
    pushStateSpy.mockClear();

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));

    expect(pushStateSpy).toHaveBeenCalledWith({ gameGuard: true }, '');
  });
});
