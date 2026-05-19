import { Component, DestroyRef, inject, OnDestroy, signal } from '@angular/core';
import { Location } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RULES_COOKIE } from './rules-version';

@Component({
  selector: 'app-rules',
  standalone: true,
  imports: [TranslateModule],
  template: `
    <div class="page" [class.has-bar]="isFirstTime()">
      <header class="page-header">
        <button class="back-btn" (click)="back()" [attr.aria-label]="'rules.back' | translate">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"
               stroke-linecap="round" stroke-linejoin="round">
            <path d="M15 18l-6-6 6-6"/>
          </svg>
        </button>
        <h1>{{ 'rules.title' | translate }}</h1>
      </header>

      <div class="content">

        <section>
          <h2>
            <span class="section-icon">🎲</span>
            {{ 'rules.endOfGame.heading' | translate }}
          </h2>
          <ul class="conditions">
            <li [innerHTML]="t('rules.endOfGame.li1')"></li>
          </ul>
          <p class="or-text">{{ 'rules.endOfGame.or' | translate }}</p>
          <ul class="conditions">
            <li [innerHTML]="t('rules.endOfGame.li2')"></li>
          </ul>
          <p class="winner-text">{{ 'rules.endOfGame.p2' | translate }}</p>
        </section>

        <section>
          <h2>
            <span class="section-icon">🔄</span>
            {{ 'rules.turns.heading' | translate }}
          </h2>
          <ul class="conditions">
            <li>
              <div class="li-row">
                <span [innerHTML]="t('rules.turns.li1')"></span>
                <span class="demo-cells">
                  <span class="demo-cell demo-ww" title="white+white"></span>
                  <span class="demo-cell demo-wc" title="white+color"></span>
                </span>
              </div>
            </li>
            <li>
              <div class="li-row">
                <span [innerHTML]="t('rules.turns.li2')"></span>
                <span class="demo-cells">
                  <span class="demo-cell demo-ww" title="white+white"></span>
                </span>
              </div>
            </li>
          </ul>
          <p class="highlight" [innerHTML]="t('rules.turns.p3')"></p>
        </section>

        <section>
          <h2>
            <span class="section-icon">⛔</span>
            {{ 'rules.punishments.heading' | translate }}
          </h2>
          <p [innerHTML]="t('rules.punishments.p1')"></p>
          <div class="punishment-demo">
            <span class="punishment-demo-label">{{ 'punishment' | translate }} : –5</span>
            <div class="punishment-demo-box taken"></div>
            <div class="punishment-demo-box taken"></div>
            <div class="punishment-demo-box"></div>
            <div class="punishment-demo-box"></div>
          </div>
        </section>

        <section>
          <h2>
            <span class="section-icon">✖️</span>
            {{ 'rules.closing.heading' | translate }}
          </h2>
          <p [innerHTML]="t('rules.closing.p1')"></p>
          <div class="tip" [innerHTML]="t('rules.closing.longoTip')"></div>
          <div class="longo-rows">

            <!-- Standard section -->
            <span class="longo-label">Standard</span>
            <div class="lock-zone longo-zone">
              <div class="longo-cell blue" [class.cell-crossed]="stdCell2()">
                <span class="cell-num">2</span>
                @if (stdCell2()) { <span class="anim-cross">✕</span> }
              </div>
              <div class="longo-lock blue" [class.lock-active]="stdLock()">
                <img src="lock_icon.svg" alt="lock" class="longo-lock-icon" />
                @if (stdLock()) { <span class="anim-cross">✕</span> }
              </div>
            </div>

            <!-- Longo section -->
            <span class="longo-label" style="margin-top:6px">Longo</span>
            <!-- Red: shows 15, 16, lock — crosses 15 then lock (skips 16) -->
            <div class="lock-zone longo-zone">
              <div class="longo-cell red" [class.cell-crossed]="rCell15()">
                <span class="cell-num">15</span>
                @if (rCell15()) { <span class="anim-cross">✕</span> }
              </div>
              <div class="longo-cell red">
                <span class="cell-num">16</span>
              </div>
              <div class="longo-lock red" [class.lock-active]="rLock()">
                <img src="lock_icon.svg" alt="lock" class="longo-lock-icon" />
                @if (rLock()) { <span class="anim-cross">✕</span> }
              </div>
            </div>
            <!-- Yellow: shows 15, 16, lock — crosses 16 then lock (skips 15) -->
            <div class="lock-zone longo-zone">
              <div class="longo-cell yellow">
                <span class="cell-num">15</span>
              </div>
              <div class="longo-cell yellow" [class.cell-crossed]="yCell16()">
                <span class="cell-num">16</span>
                @if (yCell16()) { <span class="anim-cross dark">✕</span> }
              </div>
              <div class="longo-lock yellow" [class.lock-active]="yLock()">
                <img src="lock_icon.svg" alt="lock" class="longo-lock-icon yellow-lock" />
                @if (yLock()) { <span class="anim-cross dark">✕</span> }
              </div>
            </div>
            <!-- Green: cross both closing cells (3 and 2), then close -->
            <div class="lock-zone longo-zone">
              <div class="longo-cell green" [class.cell-crossed]="bCell3()">
                <span class="cell-num">3</span>
                @if (bCell3()) { <span class="anim-cross">✕</span> }
              </div>
              <div class="longo-cell green" [class.cell-crossed]="bCell2()">
                <span class="cell-num">2</span>
                @if (bCell2()) { <span class="anim-cross">✕</span> }
              </div>
              <div class="longo-lock green" [class.lock-active]="bLock()">
                <img src="lock_icon.svg" alt="lock" class="longo-lock-icon" />
                @if (bLock()) { <span class="anim-cross">✕</span> }
              </div>
            </div>
          </div>
          <p class="lock-cross-note" [innerHTML]="t('rules.lockCross.p1')"></p>
          <p class="highlight" [innerHTML]="t('rules.lockCross.example')"></p>
        </section>

      </div>
    </div>

    @if (isFirstTime()) {
      <div class="first-time-bar">
        <button class="go-btn" (click)="goToGame()">
          {{ 'rules.goToGame' | translate }}
        </button>
      </div>
    }
  `,
  styles: [`
    :host {
      display: block;
      min-height: 100vh;
      background: #f0f0f0;
    }

    .first-time-bar {
      position: fixed;
      bottom: 0;
      left: 0;
      right: 0;
      padding: 16px 20px;
      background: linear-gradient(to top, #f0f0f0 80%, transparent);
      display: flex;
      justify-content: center;
      z-index: 20;
    }

    .go-btn {
      background: #2e7d32;
      color: white;
      border: none;
      border-radius: 10px;
      padding: 16px 48px;
      font-size: 18px;
      font-weight: 700;
      cursor: pointer;
      letter-spacing: 0.02em;
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);

      &:hover { background: #388e3c; }
      &:active { transform: scale(0.97); }
    }

    .page {
      max-width: 640px;
      margin: 0 auto;
      padding: 0 0 48px;
    }

    /* Extra room at bottom when the "Go to game" bar is visible */
    .page.has-bar {
      padding-bottom: 100px;
    }

    .page-header {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 16px 20px;
      position: sticky;
      top: 0;
      background: #f0f0f0;
      border-bottom: 1px solid #e0e0e0;
      z-index: 10;
    }

    .back-btn {
      background: none;
      border: none;
      cursor: pointer;
      padding: 4px;
      display: flex;
      align-items: center;
      color: #444;
      flex-shrink: 0;

      svg { width: 24px; height: 24px; }
      &:hover { color: #000; }
    }

    h1 {
      margin: 0;
      font-size: 20px;
      font-weight: 700;
      color: #1a1a2e;
    }

    .content {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 16px 20px 0;
    }

    section {
      background: white;
      border-radius: 12px;
      padding: 20px 24px;
      box-shadow: 0 1px 4px rgba(0, 0, 0, 0.07);
    }

    h2 {
      margin: 0 0 12px;
      font-size: 16px;
      font-weight: 700;
      color: #1a1a2e;
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .section-icon {
      font-size: 18px;
      line-height: 1;
    }

    p {
      margin: 0 0 10px;
      font-size: 15px;
      line-height: 1.6;
      color: #333;

      &:last-child { margin-bottom: 0; }
    }

    /* Allow <strong> and <em> rendered via [innerHTML] */
    :host ::ng-deep p strong { font-weight: 700; }
    :host ::ng-deep p em     { font-style: italic; }
    :host ::ng-deep .tip strong { font-weight: 700; }
    :host ::ng-deep .tip em     { font-style: italic; }

    /* Coloured underlines for WW / WC terms in the turns explanation */
    :host ::ng-deep strong.ww {
      text-decoration: underline;
      text-decoration-color: rgba(0, 229, 255, 0.9);
      text-decoration-thickness: 3px;
      text-underline-offset: 4px;
    }
    :host ::ng-deep strong.wc {
      text-decoration: underline;
      text-decoration-color: rgba(186, 66, 255, 0.9);
      text-decoration-thickness: 3px;
      text-underline-offset: 4px;
    }

    ul.conditions {
      margin: 0;
      padding-left: 20px;

      li {
        font-size: 15px;
        line-height: 1.6;
        color: #333;
        padding: 3px 0;
      }
    }

    .li-row {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .demo-cells {
      display: flex;
      gap: 6px;
      flex-shrink: 0;
    }

    @keyframes pulse-glow-demo {
      0%, 100% { box-shadow: 0 0 0 3px var(--glow-full); }
      50%       { box-shadow: 0 0 0 6px var(--glow-dim), 0 0 18px 2px var(--glow-mid); }
    }

    .demo-cell {
      display: inline-block;
      width: 32px;
      height: 32px;
      border-radius: 7px;
      background: #d32f2f;
      flex-shrink: 0;
      animation: pulse-glow-demo 1.4s ease-in-out infinite;
    }

    .demo-ww {
      --glow-full: rgba(0, 229, 255, 0.95);
      --glow-dim:  rgba(0, 229, 255, 0.50);
      --glow-mid:  rgba(0, 229, 255, 0.75);
    }

    .demo-wc {
      --glow-full: rgba(186, 66, 255, 0.95);
      --glow-dim:  rgba(186, 66, 255, 0.35);
      --glow-mid:  rgba(186, 66, 255, 0.55);
    }

    .or-text {
      margin: 4px 0;
      font-size: 13px;
      color: #888;
      font-style: italic;
      padding-left: 4px;
    }

    .winner-text {
      margin-top: 12px;
    }

    .longo-rows {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 6px;
      margin-top: 12px;
    }

    .longo-label {
      font-size: 12px;
      font-weight: 700;
      color: #555;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    /* border/radius/padding come from the global .lock-zone in styles.css */
    .longo-zone {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    .longo-cell {
      position: relative;
      width: 44px;
      height: 44px;
      border-radius: 8px;
      border: 2px solid rgba(0, 0, 0, 0.15);
      color: white;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 20px;
      font-weight: 700;
      flex-shrink: 0;
    }

    .longo-cell.red,    .longo-lock.red    { background: #d32f2f; }
    .longo-cell.yellow, .longo-lock.yellow { background: #f9a825; color: #333; }
    .longo-cell.green,  .longo-lock.green  { background: #388e3c; }
    .longo-cell.blue,   .longo-lock.blue   { background: #1565c0; }

    .cell-num {
      transition: opacity 0.2s;
    }

    .longo-cell.cell-crossed .cell-num {
      opacity: 0.25;
    }

    .anim-cross {
      position: absolute;
      font-size: 24px;
      font-weight: 900;
      line-height: 1;
      color: white;
      pointer-events: none;
    }

    .longo-lock {
      position: relative;
      width: 44px;
      height: 44px;
      border-radius: 8px;
      border: 2px solid rgba(0, 0, 0, 0.15);
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
    }

    .longo-lock-icon {
      width: 26px;
      height: 26px;
      filter: brightness(0) invert(1);
      transition: opacity 0.2s;
    }

    .yellow-lock {
      filter: brightness(0);
    }

    .longo-lock.lock-active .longo-lock-icon {
      opacity: 0.25;
    }

    .anim-cross.dark {
      color: #333;
    }

    .lock-cross-note {
      margin-top: 16px;
      padding-top: 16px;
      border-top: 1px solid #e8e8e8;
    }


    .punishment-demo {
      display: flex;
      align-items: center;
      gap: 5px;
      margin-top: 12px;
    }

    .punishment-demo-label {
      font-size: 12px;
      color: #777;
      margin-right: 2px;
    }

    .punishment-demo-box {
      position: relative;
      width: 20px;
      height: 20px;
      border: 3px solid #555;
      border-radius: 3px;
      flex-shrink: 0;
    }

    .punishment-demo-box.taken::before,
    .punishment-demo-box.taken::after {
      content: '';
      position: absolute;
      top: 50%;
      left: 50%;
      width: 65%;
      height: 3px;
      background: #555;
      border-radius: 1px;
    }

    .punishment-demo-box.taken::before {
      transform: translate(-50%, -50%) rotate(45deg);
    }

    .punishment-demo-box.taken::after {
      transform: translate(-50%, -50%) rotate(-45deg);
    }

    .highlight {
      background: #fff8e1;
      border-left: 3px solid #f9a825;
      padding: 10px 14px;
      border-radius: 0 6px 6px 0;
      margin-top: 10px;
    }

    .tip {
      background: #e8f0fe;
      border-left: 3px solid #1565c0;
      padding: 10px 14px;
      border-radius: 0 6px 6px 0;
      font-size: 14px;
      line-height: 1.5;
      color: #333;
      margin-top: 10px;
    }

    .example {
      background: #f5f5f5;
      border-radius: 8px;
      padding: 14px 16px;
      margin-top: 12px;

      p { margin-bottom: 10px; }
    }

    .score-row {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
    }

    .score-cell {
      background: white;
      border: 1px solid #ddd;
      border-radius: 6px;
      padding: 6px 10px;
      font-size: 13px;
      white-space: nowrap;
      color: #555;
    }

    .highlight-cell {
      background: #fff8c5;
      border-color: #f9a825;
      font-weight: 700;
      color: #7a5a00;
    }
  `]
})
export class RulesComponent implements OnDestroy {
  private location         = inject(Location);
  private router           = inject(Router);
  private route            = inject(ActivatedRoute);
  private sanitizer        = inject(DomSanitizer);
  private translateService = inject(TranslateService);
  private destroyRef       = inject(DestroyRef);

  // 400 days — maximum age modern browsers honour for cookies set via JS
  private static readonly MAX_AGE = 400 * 24 * 60 * 60;

  /** True when the player arrives here for the first time (cookie not yet set). */
  readonly isFirstTime = signal(
    !document.cookie.split(';').some(c => c.trim().startsWith(RULES_COOKIE + '='))
  );

  // Animation state — standard demo
  readonly stdCell2 = signal(false);
  readonly stdLock  = signal(false);

  // Animation state — red row (close at 15)
  readonly rCell15 = signal(false);
  readonly rLock   = signal(false);

  // Animation state — yellow row (close at 16)
  readonly yCell16 = signal(false);
  readonly yLock   = signal(false);

  // Animation state — blue row (cross 3 + 2, then close)
  readonly bCell3 = signal(false);
  readonly bCell2 = signal(false);
  readonly bLock  = signal(false);

  private timers: ReturnType<typeof setTimeout>[] = [];

  // Bumped on every language change so template expressions re-evaluate.
  private readonly _lang = signal(0);

  constructor() {
    // Set the cookie immediately so re-loads don't show the button again
    if (this.isFirstTime()) {
      document.cookie =
        `${RULES_COOKIE}=1; max-age=${RulesComponent.MAX_AGE}; path=/; SameSite=Lax`;
    }

    this.translateService.onLangChange
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this._lang.update(n => n + 1));

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
    this.stdCell2.set(false); this.stdLock.set(false);
    this.tick(800,  () => this.stdCell2.set(true));
    this.tick(1600, () => this.stdLock.set(true));
    this.tick(3200, () => this.runStandard());
  }

  // Red row: cross 15 → cross lock → clear → repeat
  private runRed(): void {
    this.rCell15.set(false); this.rLock.set(false);
    this.tick(800,  () => this.rCell15.set(true));
    this.tick(1600, () => this.rLock.set(true));
    this.tick(3200, () => this.runRed());
  }

  // Yellow row: cross 16 → cross lock → clear → repeat
  private runYellow(): void {
    this.yCell16.set(false); this.yLock.set(false);
    this.tick(800,  () => this.yCell16.set(true));
    this.tick(1600, () => this.yLock.set(true));
    this.tick(3200, () => this.runYellow());
  }

  // Blue row: cross 3 → cross 2 → cross lock → clear → repeat
  private runBlue(): void {
    this.bCell3.set(false); this.bCell2.set(false); this.bLock.set(false);
    this.tick(700,  () => this.bCell3.set(true));
    this.tick(1400, () => this.bCell2.set(true));
    this.tick(2100, () => this.bLock.set(true));
    this.tick(3700, () => this.runBlue());
  }

  /** Returns the translated value as trusted HTML (renders <strong>, <em>, etc.). */
  t(key: string): SafeHtml {
    void this._lang(); // track signal so Angular re-evaluates on language change
    return this.sanitizer.bypassSecurityTrustHtml(
      this.translateService.instant(key)
    );
  }

  back() { this.location.back(); }

  goToGame() {
    const returnUrl = this.route.snapshot.queryParamMap.get('return');
    if (returnUrl) {
      this.router.navigateByUrl(returnUrl);
    } else {
      this.router.navigate(['/settings']);
    }
  }
}
