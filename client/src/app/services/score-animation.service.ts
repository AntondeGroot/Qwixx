import { computed, Injectable, signal } from '@angular/core';
import { ScoreCard } from '../../generated/model/models';

export interface PlayerRow {
  id: string;
  name: string;
  scoreCard: ScoreCard;
  displayed: Record<string, number>; // colorKey -> animated points
  displayedPunishment: number;
  rank: number; // 0 = first place (top)
  lifting: boolean; // plays the lift-and-drop CSS animation
}

export interface Col {
  key: string;
  /** The final value, with any Bonus B ×2 already applied. */
  getValue: (sc: ScoreCard) => number;
  /** True when this column's value was doubled by Bonus B for this player. */
  isDoubled?: (sc: ScoreCard) => boolean;
}

/**
 * A column's value before Bonus B doubled it. The server sends only the final points, but the bonus
 * is exactly ×2 of the row's triangular score, so halving recovers the original whole number.
 */
function baseValue(col: Col, sc: ScoreCard): number {
  const value = col.getValue(sc);
  return col.isDoubled?.(sc) ? value / 2 : value;
}

function animateFrames(duration: number, onTick: (eased: number) => void): Promise<void> {
  // Zero (or negative) duration means "no animation": jump straight to the end. This is correct for
  // a 0 ms animation and keeps the unit tests deterministic — they collapse every duration to 0, so
  // they must not depend on requestAnimationFrame firing (which can stall under load and time out).
  if (duration <= 0) {
    onTick(1);
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const start = performance.now();
    const frame = (now: number) => {
      const t = Math.min(1, (now - start) / duration);
      onTick(1 - Math.pow(1 - t, 3)); // ease-out cubic
      if (t < 1) requestAnimationFrame(frame);
      else resolve();
    };
    requestAnimationFrame(frame);
  });
}

@Injectable({ providedIn: 'root' })
export class ScoreAnimationService {
  readonly playerRows = signal<PlayerRow[]>([]);
  readonly activeKey = signal<string | null>(null);
  readonly doneKeys = signal<Set<string>>(new Set());
  /** The column whose Bonus B ×2 is being revealed right now — drives the chip's attention beat. */
  readonly doublingKey = signal<string | null>(null);
  /** Columns whose ×2 chip has been revealed; it stays visible afterwards to explain the number. */
  readonly doubledKeys = signal<Set<string>>(new Set());
  readonly punishActive = signal(false);
  readonly punishDone = signal(false);
  readonly allDone = signal(false);
  readonly showModal = signal(false);
  readonly showActionBar = signal(false);

  readonly winners = computed(() => {
    const rows = this.playerRows();
    if (rows.length === 0) return [];
    const top = rows.reduce((max, r) => Math.max(max, this.displayedTotal(r)), -Infinity);
    return rows.filter((r) => this.displayedTotal(r) === top);
  });
  readonly isTie = computed(() => this.winners().length > 1);
  readonly winnerNames = computed(() =>
    this.winners()
      .map((w) => w.name)
      .join(' & '),
  );
  readonly winner = computed(() => this.playerRows().find((r) => r.rank === 0));

  reset(): void {
    this.playerRows.set([]);
    this.activeKey.set(null);
    this.doneKeys.set(new Set());
    this.doublingKey.set(null);
    this.doubledKeys.set(new Set());
    this.punishActive.set(false);
    this.punishDone.set(false);
    this.allDone.set(false);
    this.showModal.set(false);
    this.showActionBar.set(false);
  }

  displayedTotal(p: PlayerRow): number {
    return Object.values(p.displayed).reduce((s, v) => s + v, 0) + p.displayedPunishment;
  }

  isWinner(p: PlayerRow): boolean {
    return this.allDone() && this.winners().some((w) => w.id === p.id);
  }

  showInstant(cols: Col[], rows: PlayerRow[]): void {
    const finalRows = rows.map((r) => ({
      ...r,
      displayed: Object.fromEntries(cols.map((c) => [c.key, c.getValue(r.scoreCard)])),
      displayedPunishment: r.scoreCard.punishmentPoints,
    }));
    this.applyRanks(finalRows);
    this.playerRows.set(finalRows);
    this.doneKeys.set(new Set(cols.map((c) => c.key)));
    // The values already include the ×2, so the chips explaining them must be showing too.
    this.doubledKeys.set(new Set(cols.filter((c) => rows.some((r) => c.isDoubled?.(r.scoreCard))).map((c) => c.key)));
    this.punishDone.set(true);
    this.allDone.set(true);
    this.showActionBar.set(true);
  }

  async runSequence(cols: Col[], rows: PlayerRow[], ms: (n: number) => number): Promise<void> {
    const delay = (n: number) => new Promise<void>((res) => setTimeout(res, ms(n)));
    this.playerRows.set(rows);
    await delay(700);

    for (const col of cols) {
      // Count up to the plain value first and settle the standings on it, so a Bonus B ×2 reads as
      // a separate event that visibly moves the player rather than being hidden inside the count-up.
      this.activeKey.set(col.key);
      const base = new Map(rows.map((r) => [r.id, baseValue(col, r.scoreCard)]));
      await animateFrames(ms(1400), (eased) =>
        this.playerRows.update((rs) =>
          rs.map((r) => ({
            ...r,
            displayed: { ...r.displayed, [col.key]: Math.round((base.get(r.id) ?? 0) * eased) },
          })),
        ),
      );
      this.activeKey.set(null);
      this.doneKeys.update((s) => new Set([...s, col.key]));
      await delay(350);
      await this.sort(ms);
      await delay(450);

      await this.applyDoubling(col, rows, ms);
    }

    this.punishActive.set(true);
    const punishTargets = new Map(rows.map((r) => [r.id, r.scoreCard.punishmentPoints]));
    await animateFrames(ms(900), (eased) =>
      this.playerRows.update((rs) =>
        rs.map((r) => ({
          ...r,
          displayedPunishment: Math.round((punishTargets.get(r.id) ?? 0) * eased),
        })),
      ),
    );
    this.punishActive.set(false);
    this.punishDone.set(true);
    await delay(350);
    await this.sort(ms);
    await delay(900);

    this.allDone.set(true);
    await delay(1400);
    this.showModal.set(true);
  }

  /**
   * The Bonus B ×2 beat for one column: reveal the chip, hold long enough to be read, then count the
   * doubled players from their plain value up to double it and re-sort. No-op when nobody doubled
   * this column, so ordinary columns keep their original rhythm.
   */
  private async applyDoubling(col: Col, rows: PlayerRow[], ms: (n: number) => number): Promise<void> {
    const delay = (n: number) => new Promise<void>((res) => setTimeout(res, ms(n)));
    const doublers = rows.filter((r) => col.isDoubled?.(r.scoreCard));
    if (doublers.length === 0) return;

    this.doublingKey.set(col.key);
    this.doubledKeys.update((s) => new Set([...s, col.key]));
    await delay(900); // let the chip land and pulse before the number moves

    const from = new Map(doublers.map((r) => [r.id, baseValue(col, r.scoreCard)]));
    const to = new Map(doublers.map((r) => [r.id, col.getValue(r.scoreCard)]));
    await animateFrames(ms(900), (eased) =>
      this.playerRows.update((rs) =>
        rs.map((r) => {
          const start = from.get(r.id);
          if (start === undefined) return r;
          const end = to.get(r.id) ?? start;
          return { ...r, displayed: { ...r.displayed, [col.key]: Math.round(start + (end - start) * eased) } };
        }),
      ),
    );
    this.doublingKey.set(null);
    await delay(350);
    await this.sort(ms);
    await delay(450);
  }

  private applyRanks(rows: PlayerRow[]): void {
    const sorted = [...rows].sort((a, b) => this.displayedTotal(b) - this.displayedTotal(a));
    const newRank = new Map(sorted.map((r, i) => [r.id, i]));
    rows.forEach((r) => (r.rank = newRank.get(r.id) ?? r.rank));
  }

  private async sort(ms: (n: number) => number): Promise<void> {
    const delay = (n: number) => new Promise<void>((res) => setTimeout(res, ms(n)));
    const rows = this.playerRows();
    const sorted = [...rows].sort((a, b) => this.displayedTotal(b) - this.displayedTotal(a));
    const newRank = new Map(sorted.map((r, i) => [r.id, i]));

    if (rows.every((r) => newRank.get(r.id) === r.rank)) return;

    this.playerRows.update((rs) =>
      rs.map((r) => ({
        ...r,
        rank: newRank.get(r.id) ?? r.rank,
        lifting: newRank.get(r.id) !== r.rank,
      })),
    );
    await delay(900);
    this.playerRows.update((rs) => rs.map((r) => ({ ...r, lifting: false })));
  }
}
