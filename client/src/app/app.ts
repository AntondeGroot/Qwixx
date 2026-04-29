import { Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LanguageSelectorComponent } from './language-selector/language-selector.component';
import { TranslationService } from './services/translation.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, LanguageSelectorComponent],
  template: `
    <div class="app-container">
      <app-language-selector class="language-selector"></app-language-selector>
      <router-outlet />
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
  `]
})
export class App implements OnInit {
  private translationService = inject(TranslationService);

  ngOnInit() {
    this.translationService.initializeLanguage();
  }
}