import { APP_INITIALIZER, ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HTTP_INTERCEPTORS, HttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideTranslateService, TranslateLoader, TranslationObject } from '@ngx-translate/core';
import { Observable, lastValueFrom } from 'rxjs';

import { routes } from './app.routes';
import { provideApi } from '../generated/provide-api';
import { environment } from '../environments/environment';
import { LocaleInterceptor } from './interceptors/locale.interceptor';
import { TranslationService } from './services/translation.service';

const I18N_VERSION = Date.now();

export class HttpLoaderFactory implements TranslateLoader {
  constructor(private http: HttpClient) {}

  getTranslation(lang: string): Observable<TranslationObject> {
    return this.http.get<TranslationObject>(`./i18n/${lang}.json?v=${I18N_VERSION}`);
  }
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(),
    { provide: HTTP_INTERCEPTORS, useClass: LocaleInterceptor, multi: true },
    provideApi(environment.apiBaseUrl),
    provideTranslateService({
      loader: {
        provide: TranslateLoader,
        useFactory: (http: HttpClient) => new HttpLoaderFactory(http),
        deps: [HttpClient]
      }
    }),
    // Block the app from rendering until the correct locale's translations are loaded.
    // This eliminates the race condition where modals render before translations arrive.
    {
      provide: APP_INITIALIZER,
      useFactory: (translations: TranslationService) => () => lastValueFrom(translations.loadInitialLocale()),
      deps: [TranslationService],
      multi: true
    }
  ]
};
