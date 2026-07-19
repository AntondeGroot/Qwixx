import {
  afterEveryRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  signal,
  ViewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { environment } from '../../environments/environment';
import { firstValueFrom, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { GamesService, GamestatesService, PlayersService } from '../../generated/api/api';
import { GameState, RowState, ScoreCard } from '../../generated/model/models';
import { TranslateService } from '@ngx-translate/core';
import { RowComponent } from '../row/row.component';
import { SilverMarkComponent } from '../silver-mark/silver-mark.component';
import { computeBonusBProgress } from '../row/bonus-b.util';
import { isScoringColourRow } from './score-rows.util';
import { buildPreviewGame, PREVIEW_PLAYER_ID } from './score-preview.data';
import { Col, PlayerRow, ScoreAnimationService } from '../services/score-animation.service';

@Component({
  selector: 'app-score',
  imports: [RowComponent, SilverMarkComponent],
  templateUrl: './score.component.html',
  styleUrl: './score.component.css',
})
export class ScoreComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly gamesService = inject(GamesService);
  private readonly gameStatesService = inject(GamestatesService);
  private readonly playersService = inject(PlayersService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly _host = inject(ElementRef<HTMLElement>);
  private readonly animation = inject(ScoreAnimationService);
  private readonly translate = inject(TranslateService);

  // Re-expose animation state for the template
  readonly playerRows = this.animation.playerRows;
  readonly activeKey = this.animation.activeKey;
  readonly doneKeys = this.animation.doneKeys;
  readonly doublingKey = this.animation.doublingKey;
  readonly doubledKeys = this.animation.doubledKeys;
  readonly punishActive = this.animation.punishActive;
  readonly punishDone = this.animation.punishDone;
  readonly allDone = this.animation.allDone;
  readonly showModal = this.animation.showModal;
  readonly showActionBar = this.animation.showActionBar;
  readonly winner = this.animation.winner;
  readonly isTie = this.animation.isTie;
  readonly winnerNames = this.animation.winnerNames;
  readonly displayedTotal = (p: PlayerRow) => this.animation.displayedTotal(p);
  readonly isWinner = (p: PlayerRow) => this.animation.isWinner(p);

  // ── Layout measurement ────────────────────────────────────────────────────

  // Measured row stride — updated after every render by _measureRowH().
  // Defaults to 80 (the landscape value) so the first render is correct on desktop.
  private readonly _rowH = signal(80);

  // After every render, measure the actual space available for the rows-container
  // and recompute the row stride.  Using afterRender (not a setTimeout) ensures
  // the measurement and the resulting re-render complete synchronously before
  // control returns to the browser event loop, so Selenium assertions that run
  // immediately after an Angular DOM change always see the final layout.
  constructor() {
    afterEveryRender(() => {
      this._measureRowH();
      this._updateBoardLayout();
    });
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
    const rc = host.querySelector('.rows-container') as HTMLElement | null;
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
    const padTop = parseFloat(hostStyle.paddingTop) || 0;
    const padBottom = parseFloat(hostStyle.paddingBottom) || 0;
    const available = host.offsetHeight - padTop - padBottom - rcOffsetFromContent;

    if (available > 0) {
      const next = Math.min(Math.floor(available / n), 80);
      if (next !== this._rowH()) this._rowH.set(next);
    }
  }

  get rowH(): number {
    return this._rowH();
  }
  get playerRowHeight(): number {
    return Math.max(this.rowH - 8, 20);
  }

  /** Scale the final board to fit the available width after it has rendered. */
  private _updateBoardLayout(): void {
    const host = this._host.nativeElement as HTMLElement;
    const inner = host.querySelector('.final-board-inner') as HTMLElement | null;
    const outer = host.querySelector('.final-board-outer') as HTMLElement | null;
    if (!inner || !outer) return;

    inner.style.transform = 'none';
    const naturalW = inner.scrollWidth;
    const naturalH = inner.scrollHeight;
    const availW = outer.clientWidth;
    const scale = availW > 0 && naturalW > 0 ? Math.min(availW / naturalW, 1) : 1;

    inner.style.transformOrigin = 'top left';
    inner.style.transform = scale < 1 ? `scale(${scale})` : 'none';
    outer.style.height = scale < 1 ? `${Math.ceil(naturalH * scale)}px` : '';
  }

  // ── Column display state ──────────────────────────────────────────────────

  colorCols = signal<string[]>([]);
  showExtra = signal(false);
  showBonus = signal(false);

  allCols = computed(() => {
    const cols = [...this.colorCols()];
    if (this.showExtra()) cols.push('EXTRA');
    if (this.showBonus()) cols.push('BONUS');
    return cols;
  });

  // ── Player board display (score verification) ─────────────────────────────

  sessionId = '';
  playerId = '';

  // Final game state — set once the animation (or fast-show) has loaded it.
  // Used to display the player's own board at the bottom for score verification.
  readonly finalState = signal<GameState | null>(null);

  myLayout = computed(() => this.finalState()?.sheetLayouts?.[this.playerId] ?? null);
  myDoubleVariant = computed<'A' | 'B' | null>(() => {
    const s = this.finalState();
    if (s?.doubleA) return 'A';
    if (s?.doubleB) return 'B';
    return null;
  });
  myProgress = computed(() => this.finalState()?.sheetProgress?.[this.playerId] ?? null);
  myBonusBProgress = computed(() => computeBonusBProgress(this.myLayout(), this.myProgress()));
  myClosedRows = computed(() => this.finalState()?.closedRows ?? {});

  myScoreRows = computed(() => {
    const cellCount = this.myLayout()?.rows[0]?.cells.length ?? 11;
    const max = cellCount > 11 ? cellCount + 1 : 12; // 12 standard, 16 Longo
    return Array.from({ length: max }, (_, i) => ({
      crosses: i + 1,
      points: ((i + 1) * (i + 2)) / 2,
    }));
  });

  myRowStateFor(rowId: string): RowState | null {
    return this.myProgress()?.rowStates?.[rowId] ?? null;
  }

  myIsRowClosed(rowId: string): boolean {
    return rowId in this.myClosedRows();
  }

  topPx(p: PlayerRow): number {
    return p.rank * this.rowH;
  }

  // ── Sharing ───────────────────────────────────────────────────────────────

  @ViewChild('scoreCapture') private readonly scoreCaptureRef!: ElementRef<HTMLElement>;

  sharing = signal(false);
  readonly canNativeShare = navigator.maxTouchPoints > 0;

  async shareScores(): Promise<void> {
    if (this.sharing()) return;
    this.sharing.set(true);
    try {
      const { default: html2canvas } = await import('html2canvas');
      const canvas = await html2canvas(this.scoreCaptureRef.nativeElement, {
        backgroundColor: '#12122a',
        scale: window.devicePixelRatio || 2,
      });
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
      if (!blob) return;
      const file = new File([blob], 'qwixx-scores.png', { type: 'image/png' });
      if (this.canNativeShare && navigator.share && navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: 'Qwixx Scores' });
      } else {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'qwixx-scores.png';
        a.click();
        URL.revokeObjectURL(url);
      }
    } finally {
      this.sharing.set(false);
    }
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  // When ?fast=1 is in the URL every delay collapses to ~1 ms so E2E tests
  // finish in under 2 s instead of ~18 s without touching production logic.
  private readonly fast = new URLSearchParams(window.location.search).has('fast');
  private ms(normal: number): number {
    return this.fast ? 1 : normal;
  }

  ngOnInit() {
    this.animation.reset();
    // The preview route is static, so it carries no :sessionId to recognise it by — it flags itself.
    if (this.route.snapshot.data['preview']) {
      void this.runPreview();
      return;
    }
    this.sessionId = this.route.snapshot.paramMap.get('sessionId') ?? '';
    this.playerId =
      this.route.snapshot.queryParamMap.get('pid') ?? sessionStorage.getItem(`qwixx_pid_${this.sessionId}`) ?? '';
    const animationKey = `qwixx_score_shown_${this.sessionId}`;
    if (sessionStorage.getItem(animationKey)) {
      void this.showFinalState();
    } else {
      void this.runAnimation().then(() => sessionStorage.setItem(animationKey, '1'));
    }
  }

  /**
   * Dev preview (`/score/preview?players=4`): runs the real animation over synthetic Bonus B data,
   * so the ×2 reveal, the shield and the rank flips can be watched without playing a game out.
   * Replays on every visit — no sessionStorage guard, no SSE, nothing to restart.
   */
  private async runPreview(): Promise<void> {
    try {
      const players = Number(this.route.snapshot.queryParamMap.get('players') ?? 4);
      // A real layout, so the preview shows the actual Bonus B board rather than a hand-built fake.
      const layout = await firstValueFrom(this.gamesService.previewLayout({ bonusB: true }));
      const { state, scores } = buildPreviewGame(layout, players);
      this.playerId = PREVIEW_PLAYER_ID;
      this.finalState.set(state);
      const { cols, rows } = this.buildScoreData(state, scores);
      this._initPortraitRowH(rows.length);
      await this.animation.runSequence(cols, rows, (n) => this.ms(n));
    } catch (e) {
      console.error('[score] preview failed:', e);
    }
  }

  /** Skip animation and immediately render the final score state. Used on reload. */
  private async showFinalState(): Promise<void> {
    try {
      const [state, scores] = await Promise.all([
        firstValueFrom(this.gameStatesService.getGameState(this.sessionId)),
        firstValueFrom(this.gamesService.getScores(this.sessionId)),
      ]);
      this.finalState.set(state);
      const { cols, rows } = this.buildScoreData(state, scores);
      this._initPortraitRowH(rows.length);
      this.animation.showInstant(cols, rows);
      this.startRestartSse();
    } catch (e) {
      console.error('[score] showFinalState failed:', e);
      void this.router.navigate(['/']);
    }
  }

  private async runAnimation(): Promise<void> {
    try {
      const [state, scores] = await Promise.all([
        firstValueFrom(this.gameStatesService.getGameState(this.sessionId)),
        firstValueFrom(this.gamesService.getScores(this.sessionId)),
      ]);
      this.finalState.set(state);
      const { cols, rows } = this.buildScoreData(state, scores);
      this._initPortraitRowH(rows.length);
      await this.animation.runSequence(cols, rows, (n) => this.ms(n));
      this.startRestartSse();
    } catch (e) {
      console.error('[score] runAnimation failed:', e);
      void this.router.navigate(['/']);
    }
  }

  private buildScoreData(state: GameState, scores: Record<string, ScoreCard>): { cols: Col[]; rows: PlayerRow[] } {
    const layout = Object.values(state.sheetLayouts)[0];
    const colors = layout.rows.filter(isScoringColourRow).map((r) => r.cells[0]!.color as string);
    const hasExtra = Object.values(scores).some((s) => s.extraPoints > 0);
    // Bonus B always gets a column — like the colour columns, it comes from the layout, so it shows
    // a 0 rather than vanishing. Other bonus sources still only appear once someone scores one.
    const hasBonusBStrip = layout.rows.some((r) => r.bonusBStrip === true);
    const hasBonus = hasBonusBStrip || Object.values(scores).some((s) => s.bonusPoints > 0);

    this.colorCols.set(colors);
    this.showExtra.set(hasExtra);
    this.showBonus.set(hasBonus);

    const cols: Col[] = [
      ...colors.map((c) => ({
        key: c,
        getValue: (sc: ScoreCard) => sc.pointsPerColor[c] ?? 0,
        isDoubled: (sc: ScoreCard) => sc.doubledColor === c,
      })),
      ...(hasExtra ? [{ key: 'EXTRA', getValue: (sc: ScoreCard) => sc.extraPoints }] : []),
      ...(hasBonus ? [{ key: 'BONUS', getValue: (sc: ScoreCard) => sc.bonusPoints }] : []),
    ];

    const rows: PlayerRow[] = state.players.map((p, i) => ({
      id: p.id,
      name: p.name,
      scoreCard: scores[p.id],
      displayed: Object.fromEntries(cols.map((c) => [c.key, 0])),
      displayedPunishment: 0,
      rank: i,
      lifting: false,
    }));

    return { cols, rows };
  }

  // ── SSE & navigation ──────────────────────────────────────────────────────

  // Subscribes to the SSE stream. When another player triggers a restart the
  // server pushes a state with gameOver: false, and we navigate automatically.
  //
  // The server always sends the current state immediately on connection. We must
  // NOT act on that initial push — the game may still be marked gameOver:false
  // in a brief race between the subscription and the first emit. Only navigate
  // after we have seen gameOver:true at least once, confirming the game really
  // ended; a subsequent gameOver:false then means a player triggered a restart.
  private startRestartSse(): void {
    const es = new EventSource(`${environment.apiBaseUrl}/gamestates/${this.sessionId}/stream`);
    this.destroyRef.onDestroy(() => es.close());
    let seenGameOver = false;
    es.onmessage = (event: MessageEvent) => {
      const state = JSON.parse(event.data);
      if (state.gameOver) {
        seenGameOver = true;
      } else if (seenGameOver) {
        es.close();
        sessionStorage.removeItem(`qwixx_score_shown_${this.sessionId}`);
        void this.router.navigate(['/game', this.sessionId, this.playerId]);
      }
    };
  }

  /** Dismiss the winner modal and keep the score table in view. */
  t(key: string): string {
    return this.translate.instant(key);
  }

  viewScores() {
    this._initPortraitRowH(this.playerRows().length);
    this.showModal.set(false);
    this.showActionBar.set(true);
  }

  /** Navigate to settings in restart mode (preserving the current session). */
  newGame() {
    sessionStorage.removeItem(`qwixx_score_shown_${this.sessionId}`);
    if (this.sessionId && this.playerId) {
      sessionStorage.setItem('qwixx_lobby_sid', this.sessionId);
      sessionStorage.setItem('qwixx_lobby_pid', this.playerId);
    }
    void this.router.navigate(['/settings']);
  }

  /** Remove this player from the session, then navigate back to the game lobby. */
  leaveGame() {
    if (this.playerId) {
      this.playersService
        .leaveGame(this.sessionId, this.playerId)
        .pipe(catchError(() => of(null)))
        .subscribe();
    }
    window.location.href = environment.lobbyUrl;
  }
}
