import { Injectable, inject, effect, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class TranslationService {
  private translateService = inject(TranslateService);

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
    this.translateService.addLangs(this.languages.map(l => l.code));
    // Subsequent runtime language switches (user picks from the selector).
    effect(() => {
      this.translateService.use(this.currentLanguage());
    });
  }

  /** Returns the locale to use, determined from URL params then browser language. */
  detectLocale(): string {
    const queryLocale = new URLSearchParams(window.location.search).get('locale');
    const browserLang = navigator.language.split('-')[0];
    return queryLocale && this.languages.find(l => l.code === queryLocale)
      ? queryLocale
      : (this.languages.find(l => l.code === browserLang)?.code ?? 'en');
  }

  /**
   * Called by APP_INITIALIZER. Loads the correct locale's translations and blocks
   * the app from rendering until they are ready, eliminating the translation race.
   */
  loadInitialLocale(): Observable<unknown> {
    const locale = this.detectLocale();
    this.currentLanguage.set(locale);
    this.updateUrl(locale);
    return this.translateService.use(locale);
  }

  initializeLanguage() {
    // No-op: locale is already loaded by APP_INITIALIZER before any component renders.
    // Kept for backwards-compatibility with App.ngOnInit().
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