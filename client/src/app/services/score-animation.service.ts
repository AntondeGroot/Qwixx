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
  getValue: (sc: ScoreCard) => number;
}

function animateFrames(duration: number, onTick: (eased: number) => void): Promise<void> {
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
    this.punishDone.set(true);
    this.allDone.set(true);
    this.showActionBar.set(true);
  }

  async runSequence(cols: Col[], rows: PlayerRow[], ms: (n: number) => number): Promise<void> {
    const delay = (n: number) => new Promise<void>((res) => setTimeout(res, ms(n)));
    this.playerRows.set(rows);
    await delay(700);

    for (const col of cols) {
      this.activeKey.set(col.key);
      const targets = new Map(rows.map((r) => [r.id, col.getValue(r.scoreCard)]));
      await animateFrames(ms(1400), (eased) =>
        this.playerRows.update((rs) =>
          rs.map((r) => ({
            ...r,
            displayed: { ...r.displayed, [col.key]: Math.round((targets.get(r.id) ?? 0) * eased) },
          })),
        ),
      );
      this.activeKey.set(null);
      this.doneKeys.update((s) => new Set([...s, col.key]));
      await delay(350);
      await this.sort(ms);
      await delay(450);
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
