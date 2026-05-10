import { Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LanguageSelectorComponent } from './language-selector/language-selector.component';
import { RowClosureModalComponent } from './row-closure-modal/row-closure-modal.component';
import { RowClosureModalService } from './services/row-closure-modal.service';
import { TranslationService } from './services/translation.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, LanguageSelectorComponent, RowClosureModalComponent],
  template: `
    <div class="app-container">
      <app-language-selector class="language-selector"></app-language-selector>
      <router-outlet />
      <!-- Rendered here so position:fixed is relative to the real viewport,
           not the board's CSS transform (which would break it on mobile). -->
      <app-row-closure-modal
        [requests]="modal.requests()"
        [hasPendingCross]="modal.hasPendingCross()"
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

    .language-selector {
      position: fixed;
      top: 16px;
      right: 16px;
      z-index: 999;
    }

    /* In portrait the board HTML is rotated 90° CW inside the real viewport.
       Physical bottom-right = logical top-right of the rotated game. */
    @media (orientation: portrait) {
      .language-selector {
        top: auto;
        bottom: 16px;
      }
    }
  `]
})
export class App implements OnInit {
  private translationService = inject(TranslationService);
  readonly modal = inject(RowClosureModalService);

  ngOnInit() {
    this.translationService.initializeLanguage();
  }
}