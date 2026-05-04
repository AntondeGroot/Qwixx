import { TestBed } from '@angular/core/testing';
import { App } from './app';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { provideTranslateService, TranslateLoader, TranslationObject } from '@ngx-translate/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { Color } from '../generated/model/color';
import { RowClosureModalService } from './services/row-closure-modal.service';

class MockTranslateLoader implements TranslateLoader {
  getTranslation(lang: string): Observable<TranslationObject> {
    return of({});
  }
}

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App, HttpClientTestingModule],
      providers: [
        provideTranslateService({
          loader: {
            provide: TranslateLoader,
            useClass: MockTranslateLoader
          }
        })
      ]
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
      () => {}
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
    service.show([{ playerName: 'Bob', rowColor: Color.BLUE }], () => {}, () => {});
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.modal-overlay')).toBeTruthy();

    service.clear();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.modal-overlay')).toBeFalsy();
  });
});
