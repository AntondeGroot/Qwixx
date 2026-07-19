import { TestBed } from '@angular/core/testing';
import { TranslateService } from '@ngx-translate/core';
import { TranslationService } from './translation.service';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import { of } from 'rxjs';

describe('TranslationService', () => {
  let service: TranslationService;

  beforeEach(() => {
    const translateServiceMock = {
      setFallbackLang: vi.fn(),
      addLangs: vi.fn(),
      use: vi.fn(() => of({})),
    };

    TestBed.configureTestingModule({
      providers: [TranslationService, { provide: TranslateService, useValue: translateServiceMock }],
    });

    service = TestBed.inject(TranslationService);
  });

  describe('setLanguage', () => {
    it('should update currentLanguage signal', () => {
      service.setLanguage('de');
      expect(service.currentLanguage()).toBe('de');
    });

    it('should preserve existing query parameters when updating language', () => {
      const replaceStateSpy = vi.spyOn(window.history, 'replaceState');

      Object.defineProperty(window, 'location', {
        value: {
          pathname: '/settings',
          search: '?sessionid=123&playerid=456&locale=en',
        },
        writable: true,
        configurable: true,
      });

      service.setLanguage('de');

      expect(replaceStateSpy).toHaveBeenCalled();
      const newUrl = replaceStateSpy.mock.calls[0]![2];

      expect(newUrl).toContain('sessionid=123');
      expect(newUrl).toContain('playerid=456');
      expect(newUrl).toContain('locale=de');

      replaceStateSpy.mockRestore();
    });

    it('should update locale parameter only', () => {
      const replaceStateSpy = vi.spyOn(window.history, 'replaceState');

      Object.defineProperty(window, 'location', {
        value: {
          pathname: '/settings',
          search: '?locale=en',
        },
        writable: true,
        configurable: true,
      });

      service.setLanguage('fr');

      const newUrl = replaceStateSpy.mock.calls[0]![2];

      expect(newUrl).toBe('/settings?locale=fr');

      replaceStateSpy.mockRestore();
    });

    it('should add locale parameter if not present', () => {
      const replaceStateSpy = vi.spyOn(window.history, 'replaceState');

      Object.defineProperty(window, 'location', {
        value: {
          pathname: '/settings',
          search: '?sessionid=abc&playerid=def',
        },
        writable: true,
        configurable: true,
      });

      service.setLanguage('nl');

      const newUrl = replaceStateSpy.mock.calls[0]![2];

      expect(newUrl).toContain('sessionid=abc');
      expect(newUrl).toContain('playerid=def');
      expect(newUrl).toContain('locale=nl');

      replaceStateSpy.mockRestore();
    });

    it('should not update language for invalid language code', () => {
      const initialLanguage = service.currentLanguage();
      service.setLanguage('invalid-lang');
      expect(service.currentLanguage()).toBe(initialLanguage);
    });
  });

  describe('getLanguageLabel', () => {
    it('should return label for valid language code', () => {
      expect(service.getLanguageLabel('en')).toBe('English');
      expect(service.getLanguageLabel('de')).toBe('Deutsch');
      expect(service.getLanguageLabel('fr')).toBe('Français');
    });

    it('should return code itself for invalid language code', () => {
      expect(service.getLanguageLabel('invalid')).toBe('invalid');
    });
  });
});
