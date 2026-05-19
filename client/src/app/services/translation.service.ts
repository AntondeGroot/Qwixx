import { Injectable, inject, signal } from '@angular/core';
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
    { code: 'it', label: 'Italiano' },
    { code: 'nl', label: 'Nederlands' },
    { code: 'nb', label: 'Norsk Bokmål' }
  ];

  currentLanguage = signal<string>('en');

  constructor() {
    this.translateService.setDefaultLang('en');
    this.translateService.addLangs(this.languages.map(l => l.code));
    // No effect here: loadInitialLocale() calls translateService.use() directly,
    // and setLanguage() calls it directly too.  An effect would fire as a microtask
    // during the APP_INITIALIZER await and start a duplicate HTTP request for the
    // same locale file — on a slow server the second response arrives after the app
    // has already rendered, causing onLangChange to fire again and briefly show raw
    // translation keys instead of translated strings.
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
   *
   * All OTHER language files are kicked off in the background so that when the user
   * switches languages later, ngx-translate serves them from cache and fires
   * changeLang() synchronously — no HTTP wait, no translate pipe briefly showing
   * the old language while the new file loads.
   *
   * ngx-translate's own `lastUseLanguage` guard ensures a slow preload response
   * never flips currentLang away from the initial locale.
   */
  loadInitialLocale(): Observable<unknown> {
    const locale = this.detectLocale();
    this.currentLanguage.set(locale);
    this.updateUrl(locale);
    // Start background preloads for all other languages (fire-and-forget).
    // use(locale) is called LAST so lastUseLanguage ends up = locale.
    this.languages
      .filter(l => l.code !== locale)
      .forEach(l => this.translateService.use(l.code));
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
      this.translateService.use(language);
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
