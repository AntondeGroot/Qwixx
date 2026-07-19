import { Component, DestroyRef, inject, OnDestroy, signal } from '@angular/core';
import { Location } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { TranslateService } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RULES_COOKIE } from './rules-version';

@Component({
  selector: 'app-rules',
  imports: [],
  templateUrl: './rules.component.html',
  styleUrl: './rules.component.css',
})
export class RulesComponent implements OnDestroy {
  private readonly location = inject(Location);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly translateService = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  // 400 days — maximum age modern browsers honour for cookies set via JS
  private static readonly MAX_AGE = 400 * 24 * 60 * 60;

  /** True when the player arrives here for the first time (cookie not yet set). */
  readonly isFirstTime = signal(!document.cookie.split(';').some((c) => c.trim().startsWith(RULES_COOKIE + '=')));

  // Animation state — standard demo
  readonly stdCell2 = signal(false);
  readonly stdLock = signal(false);

  // Animation state — red row (close at 15)
  readonly rCell15 = signal(false);
  readonly rLock = signal(false);

  // Animation state — yellow row (close at 16)
  readonly yCell16 = signal(false);
  readonly yLock = signal(false);

  // Animation state — blue row (cross 3 + 2, then close)
  readonly bCell3 = signal(false);
  readonly bCell2 = signal(false);
  readonly bLock = signal(false);

  private readonly timers: ReturnType<typeof setTimeout>[] = [];

  // Bumped on every language change so template expressions re-evaluate.
  private readonly _lang = signal(0);

  constructor() {
    // Set the cookie immediately so re-loads don't show the button again
    if (this.isFirstTime()) {
      document.cookie = `${RULES_COOKIE}=1; max-age=${RulesComponent.MAX_AGE}; path=/; SameSite=Lax`;
    }

    this.translateService.onLangChange
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this._lang.update((n) => n + 1));

    this.runStandard();
    // Stagger longo row animations so they are never in the same phase
    this.runRed();
    this.tick(1000, () => this.runYellow());
    this.tick(2000, () => this.runBlue());
  }

  ngOnDestroy(): void {
    this.timers.forEach(clearTimeout);
  }

  private tick(ms: number, fn: () => void): void {
    this.timers.push(setTimeout(fn, ms));
  }

  // Standard: cross 2 → cross lock → clear → repeat
  private runStandard(): void {
    this.stdCell2.set(false);
    this.stdLock.set(false);
    this.tick(800, () => this.stdCell2.set(true));
    this.tick(1600, () => this.stdLock.set(true));
    this.tick(3200, () => this.runStandard());
  }

  // Red row: cross 15 → cross lock → clear → repeat
  private runRed(): void {
    this.rCell15.set(false);
    this.rLock.set(false);
    this.tick(800, () => this.rCell15.set(true));
    this.tick(1600, () => this.rLock.set(true));
    this.tick(3200, () => this.runRed());
  }

  // Yellow row: cross 16 → cross lock → clear → repeat
  private runYellow(): void {
    this.yCell16.set(false);
    this.yLock.set(false);
    this.tick(800, () => this.yCell16.set(true));
    this.tick(1600, () => this.yLock.set(true));
    this.tick(3200, () => this.runYellow());
  }

  // Blue row: cross 3 → cross 2 → cross lock → clear → repeat
  private runBlue(): void {
    this.bCell3.set(false);
    this.bCell2.set(false);
    this.bLock.set(false);
    this.tick(700, () => this.bCell3.set(true));
    this.tick(1400, () => this.bCell2.set(true));
    this.tick(2100, () => this.bLock.set(true));
    this.tick(3700, () => this.runBlue());
  }

  t(key: string): string {
    this._lang(); // read the language signal so this re-runs on a language change
    return this.translateService.instant(key);
  }

  /** Returns the translated value as trusted HTML (renders <strong>, <em>, etc.). */
  html(key: string): SafeHtml {
    this._lang(); // read the language signal so this re-runs on a language change
    // Safe: the input is our own static translation JSON (developer-authored, never user input),
    // so bypassing sanitization to render the intended inline markup carries no injection risk.
    // eslint-disable-next-line sonarjs/no-angular-bypass-sanitization
    return this.sanitizer.bypassSecurityTrustHtml(this.translateService.instant(key));
  }

  back() {
    this.location.back();
  }

  goToGame() {
    const returnUrl = this.route.snapshot.queryParamMap.get('return');
    if (returnUrl) {
      void this.router.navigateByUrl(returnUrl);
    } else {
      void this.router.navigate(['/settings']);
    }
  }
}
