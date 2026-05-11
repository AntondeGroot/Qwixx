import { afterEveryRender, Component, computed, DestroyRef, ElementRef, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { environment } from '../../environments/environment';
import { firstValueFrom, interval, EMPTY, of } from 'rxjs';
import { catchError, switchMap } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule } from '@ngx-translate/core';
import { GamesService } from '../../generated/api/games.service';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { PlayersService } from '../../generated/api/players.service';
import { ScoreCard } from '../../generated/model/scoreCard';

const delay = (ms: number): Promise<void> => new Promise(resolve => setTimeout(resolve, ms));

interface PlayerRow {
  id:                  string;
  name:                string;
  scoreCard:           ScoreCard;
  displayed:           Record<string, number>; // colorKey -> animated points
  displayedPunishment: number;
  rank:                number;   // 0 = first place (top)
  lifting:             boolean;  // plays the lift-and-drop CSS animation
}

interface Col {
  key:      string;
  getValue: (sc: ScoreCard) => number;
}

@Component({
  selector: 'app-score',
  standalone: true,
  imports: [TranslateModule],
  templateUrl:  './score.component.html',
  styleUrl: './score.component.css'
})
export class ScoreComponent implements OnInit {
  private route             = inject(ActivatedRoute);
  private router            = inject(Router);
  private gamesService      = inject(GamesService);
  private gameStatesService = inject(GamestatesService);
  private playersService    = inject(PlayersService);
  private destroyRef        = inject(DestroyRef);
  private readonly _host    = inject(ElementRef<HTMLElement>);

  // Measured row stride — updated after every render by _measureRowH().
  // Defaults to 80 (the landscape value) so the first render is correct on desktop.
  private readonly _rowH = signal(80);

  // After every render, measure the actual space available for the rows-container
  // and recompute the row stride.  Using afterRender (not a setTimeout) ensures
  // the measurement and the resulting re-render complete synchronously before
  // control returns to the browser event loop, so Selenium assertions that run
  // immediately after an Angular DOM change always see the final layout.
  constructor() {
    afterEveryRender(() => this._measureRowH());
  }

  // Called synchronously before playerRows.set() so Angular batches both signal
  // updates into one render.  160 px is a conservative overhead estimate for
  // title + header + padding in portrait, giving a safety margin of ~60 px over
  // the typical actual value (~96 px).  afterEveryRender refines to the exact
  // DOM measurement on subsequent renders.
  private _initPortraitRowH(n: number): void {
    if (window.innerHeight > window.innerWidth && n > 0) {
      const pre = Math.min(Math.floor((window.innerWidth - 160) / n), 80);
      this._rowH.set(Math.max(pre, 20));
    }
  }

  private _measureRowH(): void {
    if (window.innerHeight <= window.innerWidth) {
      if (this._rowH() !== 80) this._rowH.set(80);
      return;
    }
    const n = this.playerRows().length;
    if (n === 0) return;

    const host = this._host.nativeElement as HTMLElement;
    const rc   = host.querySelector('.rows-container') as HTMLElement | null;
    if (!rc) return;

    // offsetTop gives the distance from the element's top border to the inner
    // (content-area) top of its offsetParent.  Walking the chain to the host
    // gives the total offset from the host's content-area top.
    let rcOffsetFromContent = 0;
    let el: HTMLElement | null = rc;
    while (el && el !== host) {
      rcOffsetFromContent += el.offsetTop;
      el = el.offsetParent as HTMLElement | null;
    }

    // offsetHeight on the host includes padding (box-sizing: border-box).
    // Subtract both padding halves to get the content-area height, then
    // subtract the rows-container's offset to find the space below it.
    const hostStyle = window.getComputedStyle(host);
    const padTop    = parseFloat(hostStyle.paddingTop)    || 0;
    const padBottom = parseFloat(hostStyle.paddingBottom) || 0;
    const available = host.offsetHeight - padTop - padBottom - rcOffsetFromContent;

    if (available > 0) {
      const next = Math.min(Math.floor(available / n), 80);
      if (next !== this._rowH()) this._rowH.set(next);
    }
  }

  // Public accessors read by the template.
  get rowH(): number         { return this._rowH(); }
  get playerRowHeight(): number { return Math.max(this.rowH - 8, 20); }

  // When ?fast=1 is in the URL every delay collapses to ~1 ms so E2E tests
  // finish in under 2 s instead of ~18 s without touching production logic.
  private readonly fast = new URLSearchParams(window.location.search).has('fast');
  private ms(normal: number): number { return this.fast ? 1 : normal; }

  sessionId = '';
  playerId  = '';

  // Column descriptors (colour order from server layout)
  colorCols  = signal<string[]>([]); // e.g. ['RED','YELLOW','GREEN','BLUE']
  showExtra  = signal(false);
  showBonus  = signal(false);

  // All non-punishment column keys in display order (drives @for in template)
  allCols = computed(() => {
    const cols = [...this.colorCols()];
    if (this.showExtra()) cols.push('EXTRA');
    if (this.showBonus()) cols.push('BONUS');
    return cols;
  });

  // Player rows — DOM order is fixed; `rank` drives absolute `top` position
  playerRows = signal<PlayerRow[]>([]);

  // Animation bookkeeping
  activeKey    = signal<string | null>(null);   // column currently counting up
  doneKeys     = signal<Set<string>>(new Set()); // columns finished (gold tint)
  punishActive = signal(false);
  punishDone   = signal(false);
  allDone      = signal(false);
  showModal    = signal(false);
  showActionBar = signal(false); // true after the winner modal is dismissed via "View Scores"

  winner = computed(() => this.playerRows().find(r => r.rank === 0));

  displayedTotal(p: PlayerRow): number {
    return Object.values(p.displayed).reduce((s, v) => s + v, 0) + p.displayedPunishment;
  }

  topPx(p: PlayerRow): number { return p.rank * this.rowH; }

  ngOnInit() {
    this.sessionId = this.route.snapshot.paramMap.get('sessionId') ?? '';
    this.playerId  = this.route.snapshot.queryParamMap.get('pid') ??
                     sessionStorage.getItem(`qwixx_pid_${this.sessionId}`) ?? '';
    const animationKey = `qwixx_score_shown_${this.sessionId}`;
    if (sessionStorage.getItem(animationKey)) {
      this.showFinalState();
    } else {
      this.runAnimation().then(() => sessionStorage.setItem(animationKey, '1'));
    }
  }

  /** Skip animation and immediately render the final score state. Used on reload. */
  private async showFinalState(): Promise<void> {
    try {
      const [state, scores] = await Promise.all([
        firstValueFrom(this.gameStatesService.getGameState(this.sessionId)),
        firstValueFrom(this.gamesService.getScores(this.sessionId)),
      ]);

      const layout   = Object.values(state.sheetLayouts)[0];
      const colors   = layout.rows.map(r => r.cells[0]!.color as string);
      const hasExtra = Object.values(scores).some(s => s.extraPoints > 0);
      const hasBonus = Object.values(scores).some(s => s.bonusPoints > 0);
      this.colorCols.set(colors);
      this.showExtra.set(hasExtra);
      this.showBonus.set(hasBonus);

      const cols: Col[] = [
        ...colors.map(c => ({ key: c, getValue: (sc: ScoreCard) => sc.pointsPerColor[c] ?? 0 })),
        ...(hasExtra ? [{ key: 'EXTRA', getValue: (sc: ScoreCard) => sc.extraPoints }] : []),
        ...(hasBonus ? [{ key: 'BONUS', getValue: (sc: ScoreCard) => sc.bonusPoints }] : []),
      ];

      const rows: PlayerRow[] = state.players.map((p, i) => ({
        id:                  p.id,
        name:                p.name,
        scoreCard:           scores[p.id],
        displayed:           Object.fromEntries(cols.map(c => [c.key, c.getValue(scores[p.id])])),
        displayedPunishment: scores[p.id].punishmentPoints,
        rank:                i,
        lifting:             false,
      }));

      // Sort into final rank order immediately
      const sorted  = [...rows].sort((a, b) => this.displayedTotal(b) - this.displayedTotal(a));
      const newRank = new Map(sorted.map((r, i) => [r.id, i]));
      rows.forEach(r => { r.rank = newRank.get(r.id) ?? r.rank; });

      this._initPortraitRowH(rows.length);
      this.playerRows.set(rows);
      this.doneKeys.set(new Set(cols.map(c => c.key)));
      this.punishDone.set(true);
      this.allDone.set(true);
      this.showActionBar.set(true);
      this.startRestartPoll();
    } catch {
      this.router.navigate(['/']);
    }
  }

  private async runAnimation(): Promise<void> {
    try {
      const [state, scores] = await Promise.all([
        firstValueFrom(this.gameStatesService.getGameState(this.sessionId)),
        firstValueFrom(this.gamesService.getScores(this.sessionId)),
      ]);

      // Derive column structure from any player's layout
      const layout   = Object.values(state.sheetLayouts)[0];
      const colors   = layout.rows.map(r => r.cells[0]!.color as string);
      const hasExtra = Object.values(scores).some(s => s.extraPoints  > 0);
      const hasBonus = Object.values(scores).some(s => s.bonusPoints  > 0);

      this.colorCols.set(colors);
      this.showExtra.set(hasExtra);
      this.showBonus.set(hasBonus);

      // Build ordered column list for the animation sequence
      const cols: Col[] = [
        ...colors.map(c => ({ key: c, getValue: (sc: ScoreCard) => sc.pointsPerColor[c] ?? 0 })),
        ...(hasExtra ? [{ key: 'EXTRA', getValue: (sc: ScoreCard) => sc.extraPoints  }] : []),
        ...(hasBonus ? [{ key: 'BONUS', getValue: (sc: ScoreCard) => sc.bonusPoints  }] : []),
      ];

      // Initialise all player rows at zero
      const initDisplayed = (sc: ScoreCard): Record<string, number> =>
        Object.fromEntries(cols.map(c => [c.key, 0]));

      const rows: PlayerRow[] = state.players.map((p, i) => ({
        id:                  p.id,
        name:                p.name,
        scoreCard:           scores[p.id],
        displayed:           initDisplayed(scores[p.id]),
        displayedPunishment: 0,
        rank:                i,
        lifting:             false,
      }));
      this._initPortraitRowH(rows.length);
      this.playerRows.set(rows);

      await delay(this.ms(700));

      // ── Animate each column one by one ──────────────────────────────────
      for (const col of cols) {
        this.activeKey.set(col.key);

        const targets = new Map(rows.map(r => [r.id, col.getValue(r.scoreCard)]));
        await this.animate(this.ms(1400), eased =>
          this.playerRows.update(rs => rs.map(r => ({
            ...r,
            displayed: { ...r.displayed, [col.key]: Math.round((targets.get(r.id) ?? 0) * eased) },
          })))
        );

        this.activeKey.set(null);
        this.doneKeys.update(s => new Set([...s, col.key]));
        await delay(this.ms(350));
        await this.sort();
        await delay(this.ms(450));
      }

      // ── Punishment column ────────────────────────────────────────────────
      this.punishActive.set(true);
      const punishTargets = new Map(rows.map(r => [r.id, r.scoreCard.punishmentPoints]));
      await this.animate(this.ms(900), eased =>
        this.playerRows.update(rs => rs.map(r => ({
          ...r,
          displayedPunishment: Math.round((punishTargets.get(r.id) ?? 0) * eased),
        })))
      );
      this.punishActive.set(false);
      this.punishDone.set(true);
      await delay(this.ms(350));
      await this.sort();
      await delay(this.ms(900));

      this.allDone.set(true);
      await delay(this.ms(1400));
      this.showModal.set(true);

      // After the winner modal appears, poll for a game restart so all players
      // are redirected automatically when any player starts a new game.
      this.startRestartPoll();

    } catch {
      this.router.navigate(['/']);
    }
  }

  // Polls the game state every 2 s. When another player triggers a restart
  // the game transitions from FINISHED back to IN_PROGRESS (gameOver: false),
  // and we navigate every watcher back to their game board automatically.
  private startRestartPoll(): void {
    interval(2000).pipe(
      takeUntilDestroyed(this.destroyRef),
      switchMap(() =>
        this.gameStatesService.getGameState(this.sessionId).pipe(catchError(() => EMPTY))
      ),
    ).subscribe(state => {
      if (!state.gameOver) {
        this.router.navigate(['/game', this.sessionId, this.playerId]);
      }
    });
  }

  private animate(duration: number, onTick: (eased: number) => void): Promise<void> {
    return new Promise(resolve => {
      const start = performance.now();
      const frame = (now: number) => {
        const t     = Math.min(1, (now - start) / duration);
        const eased = 1 - Math.pow(1 - t, 3); // ease-out cubic
        onTick(eased);
        t < 1 ? requestAnimationFrame(frame) : resolve();
      };
      requestAnimationFrame(frame);
    });
  }

  private async sort(): Promise<void> {
    const rows    = this.playerRows();
    const sorted  = [...rows].sort((a, b) => this.displayedTotal(b) - this.displayedTotal(a));
    const newRank = new Map(sorted.map((r, i) => [r.id, i]));

    if (rows.every(r => newRank.get(r.id) === r.rank)) return;

    this.playerRows.update(rs => rs.map(r => ({
      ...r,
      rank:    newRank.get(r.id) ?? r.rank,
      lifting: newRank.get(r.id) !== r.rank,
    })));

    await delay(this.ms(900));
    this.playerRows.update(rs => rs.map(r => ({ ...r, lifting: false })));
  }

  /** Dismiss the winner modal and keep the score table in view. */
  viewScores() {
    this.showModal.set(false);
    this.showActionBar.set(true);
  }

  /** Navigate to settings in restart mode (preserving the current session). */
  newGame() {
    // Store restart context so the settings component can pick it up.
    if (this.sessionId && this.playerId) {
      sessionStorage.setItem('qwixx_lobby_sid', this.sessionId);
      sessionStorage.setItem('qwixx_lobby_pid', this.playerId);
    }
    this.router.navigate(['/settings']);
  }

  /** Remove this player from the session, then navigate back to the game lobby. */
  leaveGame() {
    if (this.playerId) {
      this.playersService.leaveGame(this.sessionId, this.playerId)
        .pipe(catchError(() => of(null)))
        .subscribe();
    }
    window.location.href = environment.lobbyUrl;
  }
}
