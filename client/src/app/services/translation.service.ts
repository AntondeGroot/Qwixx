import { Injectable, inject, effect } from '@angular/core';
import { Router } from '@angular/router';
import { TranslateService } from '@ngx-translate/core';
import { signal } from '@angular/core';

@Injectable({
  providedIn: 'root'
})
export class TranslationService {
  private translateService = inject(TranslateService);
  private router = inject(Router);

  readonly languages = [
    { code: 'en', label: 'English' },
    { code: 'de', label: 'Deutsch' },
    { code: 'fr', label: 'Français' },
    { code: 'nl', label: 'Nederlands' },
    { code: 'nb', label: 'Norsk Bokmål' }
  ];

  currentLanguage = signal<string>('en');

  constructor() {
    this.translateService.setDefaultLang('en');
    const langCodes = this.languages.map(l => l.code);
    this.translateService.addLangs(langCodes);

    // Preload all language files to ensure instant() works for all languages
    langCodes.forEach(lang => {
      this.translateService.use(lang).subscribe();
    });

    // Set back to English after preloading
    this.translateService.use('en').subscribe();

    effect(() => {
      this.translateService.use(this.currentLanguage());
    });
  }

  initializeLanguage() {
    const queryLocale = new URLSearchParams(window.location.search).get('locale');
    const browserLang = navigator.language.split('-')[0];

    const locale = queryLocale ||
      (this.languages.find(l => l.code === browserLang)?.code) ||
      'en';

    this.setLanguage(locale);
  }

  setLanguage(language: string) {
    if (this.languages.find(l => l.code === language)) {
      this.currentLanguage.set(language);
      this.updateUrl(language);
    }
  }

  private updateUrl(language: string) {
    const params = new URLSearchParams(window.location.search);
    params.set('locale', language);
    const queryString = params.toString();
    const newUrl = queryString ? `${window.location.pathname}?${queryString}` : window.location.pathname;
    window.history.replaceState({}, '', newUrl);
  }

  getLanguageLabel(code: string): string {
    return this.languages.find(l => l.code === code)?.label || code;
  }
}