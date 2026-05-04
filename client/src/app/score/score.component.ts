import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { GamesService } from '../../generated/api/games.service';
import { GamestatesService } from '../../generated/api/gamestates.service';
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

  private readonly ROW_H = 80;  // px — keep in sync with CSS

  // When ?fast=1 is in the URL every delay collapses to ~1 ms so E2E tests
  // finish in under 2 s instead of ~18 s without touching production logic.
  private readonly fast = new URLSearchParams(window.location.search).has('fast');
  private ms(normal: number): number { return this.fast ? 1 : normal; }

  sessionId = '';

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

  topPx(p: PlayerRow): number { return p.rank * this.ROW_H; }

  ngOnInit() {
    this.sessionId = this.route.snapshot.paramMap.get('sessionId') ?? '';
    this.runAnimation();
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

    } catch {
      this.router.navigate(['/settings']);
    }
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

  goToSettings() { this.router.navigate(['/settings']); }
}
