import { Component, inject, OnInit } from '@angular/core';
import { NavigationEnd, RouterOutlet, Router } from '@angular/router';
import { Location } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map, take } from 'rxjs/operators';
import { RULES_COOKIE } from './rules/rules-version';
import { LanguageSelectorComponent } from './language-selector/language-selector.component';
import { RowClosureModalComponent } from './row-closure-modal/row-closure-modal.component';
import { RowClosureModalService } from './services/row-closure-modal.service';
import { TranslationService } from './services/translation.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, LanguageSelectorComponent, RowClosureModalComponent],
  template: `
    <div class="app-container">
      <div class="top-controls">
        <button class="rules-btn" (click)="openRules()" aria-label="How to play">
          <!-- Book / study icon adapted from study-icon.svg, using currentColor -->
          <svg viewBox="0 0 122.88 96.16" xmlns="http://www.w3.org/2000/svg" class="rules-icon" [class.upright]="isOnRules()">
            <path opacity="0.9" fill="currentColor" d="M108.82,14.33c-0.02-0.18-0.05-0.37-0.05-0.57c0-0.19,0.01-0.38,0.05-0.57V0.7
              c-8.76-0.83-17.79,0.13-25.68,3.12c-7.37,2.8-13.73,7.39-17.86,13.98v71.15c6.43-4.29,13-7.82,19.75-10.22
              c7.69-2.74,15.6-4.04,23.79-3.39V14.33L108.82,14.33L108.82,14.33z M57.71,88.21V17.68C53.74,10.68,47.32,6,40.08,3.22
              C31.87,0.08,22.64-0.63,14.6,0.51l-0.43,75.05c8.77-0.32,17.36,0.8,25.43,3.44C46.03,81.09,52.12,84.16,57.71,88.21z"/>
            <path opacity="0.5" fill="currentColor" d="M6.62,79.25l0.35-61.69H0v78.5c9.57-2.47,19.17-4.04,28.85-4.11
              c8.93-0.05,17.86,1.19,26.81,4.22c-5.56-4.5-11.76-7.82-18.38-9.97c-8.33-2.72-17.34-3.62-26.58-2.83
              c-2.09,0.17-3.91-1.38-4.09-3.46C6.59,79.68,6.59,79.46,6.62,79.25z M68.95,95.59c8.37-2.63,16.72-3.71,25.08-3.66
              c9.67,0.05,19.28,1.64,28.85,4.11V17.56h-6.48v62.03c0,2.09-1.7,3.79-3.79,3.79c-0.3,0-0.59-0.03-0.87-0.1
              c-8.29-1.3-16.32-0.22-24.16,2.57C81.26,88.1,75.06,91.47,68.95,95.59z"/>
          </svg>
        </button>
        <app-language-selector></app-language-selector>
      </div>
      <router-outlet />
      <!-- Rendered here so position:fixed is relative to the real viewport,
           not the board's CSS transform (which would break it on mobile). -->
      <app-row-closure-modal
        [requests]="modal.requests()"
        [hasPendingCross]="modal.hasPendingCross()"
        [confirmEndsRound]="modal.confirmEndsRound()"
        [lockConfirmRequest]="modal.lockConfirmRequest()"
        (confirmSelection)="modal.confirmFn?.()"
        (changeSelection)="modal.changeFn?.()"
        (lockYes)="modal.lockConfirmYesFn?.()"
        (lockNo)="modal.lockConfirmNoFn?.()">
      </app-row-closure-modal>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      height: 100%;
    }

    .app-container {
      position: relative;
      height: 100%;
    }

    .top-controls {
      position: fixed;
      top: 16px;
      right: 16px;
      z-index: 999;
      display: flex;
      align-items: center;
      gap: 0;
    }

    .rules-btn {
      background: none;
      border: none;
      padding: 8px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      color: inherit;
      width: 40px;
      height: 40px;

      &:hover { opacity: 0.7; }
    }

    .rules-icon {
      width: 24px;
      height: 24px;
    }

    /* In portrait the board HTML is rotated 90° CW inside the real viewport.
       Physical bottom-right = logical top-right of the rotated game. */
    @media (orientation: portrait) {
      .top-controls {
        top: auto;
        bottom: 16px;
      }

      /* Rotate the icon to match the board — except on the rules page,
         which is a normal portrait layout and needs no rotation. */
      .rules-icon {
        transform: rotate(90deg);
      }

      .rules-icon.upright {
        transform: none;
      }
    }
  `]
})
export class App implements OnInit {
  private translationService = inject(TranslationService);
  private router             = inject(Router);
  private location           = inject(Location);
  readonly modal = inject(RowClosureModalService);

  readonly isOnRules = toSignal(
    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd),
      map(() => this.router.url.startsWith('/rules'))
    ),
    { initialValue: this.router.url.startsWith('/rules') }
  );

  ngOnInit() {
    this.translationService.initializeLanguage();

    // First-time players have not seen the rules yet — redirect them automatically.
    if (!this.hasSeenRules()) {
      this.router.events.pipe(
        filter(e => e instanceof NavigationEnd),
        take(1)
      ).subscribe((e) => {
        const url = (e as NavigationEnd).urlAfterRedirects;
        if (url.startsWith('/game/') || url.startsWith('/settings')) {
          this.router.navigate(['/rules'], { queryParams: { return: url } });
        }
      });
    }
  }

  private hasSeenRules(): boolean {
    return document.cookie.split(';').some(c => c.trim().startsWith(RULES_COOKIE + '='));
  }

  openRules() {
    if (this.router.url.startsWith('/rules')) {
      this.location.back();
    } else {
      this.router.navigate(['/rules']);
    }
  }
}
