import { CellTag, Color, GameState, ScoreCard, SheetLayout, SheetRow } from '../../generated/model/models';
import { isScoringColourRow } from './score-rows.util';
import { bonusKindOf } from '../row/bonus-b.util';

/**
 * Synthetic final-game data for the score screen's dev preview (`/score/preview`).
 *
 * The layout is a real one fetched from the stateless /game-options/preview endpoint, so the tiles,
 * the Bonus B strip and the row shapes are exactly what a real game produces. Only the players,
 * their progress and their score cards are invented — enough to exercise the parts of the score
 * screen that are otherwise hard to reach: the ×2 reveal and its re-sort, the no-penalty shield,
 * the BONUS column, and rank changes between several players.
 */

const NAMES = ['Alice', 'Bob', 'Cara', 'Dan', 'Eve', 'Frank'];

/** How each preview player is set up. Ordered so the ×2 visibly changes the standings. */
interface PreviewPlayer {
  crosses: Record<string, number>; // colour → number of crosses
  doubledColor?: Color; // Bonus B ×2 landed on this row
  noPenalty?: boolean; // Bonus B shield
  bonusPoints: number; // e.g. the +13 tile
  punishments: number;
}

/**
 * The cast, tuned so the ×2 is worth watching. Ranks sort on the running total and RED counts first,
 * so the flip has to happen inside RED itself: Alice's plain 15 leaves her behind Bob's 21, and her
 * double to 30 takes the lead. Every doubled row is also that player's fewest, as the real bonus
 * requires — see the guards in score-preview.data.spec.ts.
 */
const CAST: PreviewPlayer[] = [
  {
    crosses: { RED: 5, YELLOW: 5, GREEN: 6, BLUE: 7 },
    doubledColor: 'RED',
    noPenalty: true,
    bonusPoints: 13,
    punishments: 2,
  },
  { crosses: { RED: 6, YELLOW: 5, GREEN: 2, BLUE: 4 }, bonusPoints: 0, punishments: 1 },
  { crosses: { RED: 2, YELLOW: 4, GREEN: 5, BLUE: 5 }, doubledColor: 'RED', bonusPoints: 13, punishments: 0 },
  { crosses: { RED: 4, YELLOW: 4, GREEN: 4, BLUE: 4 }, noPenalty: true, bonusPoints: 0, punishments: 3 },
  { crosses: { RED: 7, YELLOW: 1, GREEN: 3, BLUE: 2 }, doubledColor: 'YELLOW', bonusPoints: 0, punishments: 0 },
  { crosses: { RED: 5, YELLOW: 5, GREEN: 5, BLUE: 1 }, bonusPoints: 13, punishments: 4 },
];

const triangular = (n: number): number => (n * (n + 1)) / 2;

/** Mirrors the server's scoring so the preview's numbers add up the way a real score card would. */
function buildScoreCard(p: PreviewPlayer, crosses: Record<string, number>): ScoreCard {
  const pointsPerColor: Record<string, number> = {};
  for (const [colour, n] of Object.entries(crosses)) {
    pointsPerColor[colour] = triangular(n) * (p.doubledColor === colour ? 2 : 1);
  }
  const punishmentPoints = p.noPenalty ? 0 : p.punishments * -5;
  const total = Object.values(pointsPerColor).reduce((s, v) => s + v, 0) + p.bonusPoints + punishmentPoints;

  return {
    crossesPerColor: { ...crosses },
    pointsPerColor,
    extraCrosses: 0,
    extraPoints: 0,
    bonusPoints: p.bonusPoints,
    punishmentPoints,
    total,
    doubledColor: p.doubledColor,
    noPenalty: p.noPenalty ?? false,
  } as ScoreCard;
}

/** The Bonus B kinds this player's score card claims — the pairs their board has to back up. */
function achievedKinds(p: PreviewPlayer): Set<string> {
  const achieved = new Set<string>();
  if (p.doubledColor) achieved.add(CellTag.BonusKindEnum.DOUBLE_FEWEST);
  if (p.noPenalty) achieved.add(CellTag.BonusKindEnum.NO_PENALTY);
  if (p.bonusPoints > 0) achieved.add(CellTag.BonusKindEnum.PLUS_13);
  return achieved;
}

/**
 * Picks which cells to cross in one colour row. The count is all that scores, but *which* cells
 * decides the strip's N/2 counters, which count crossed bonus boxes in the colour rows: every box of
 * an achieved kind gets crossed so its counter reads 2/2, and boxes of unachieved kinds are skipped
 * so theirs stay at 0/2. Skipping cells is legal in Qwixx, so any subset is a real board state.
 */
function chooseCrosses(row: SheetRow, target: number, achieved: Set<string>): string[] {
  const required = row.cells.filter((c) => {
    const kind = bonusKindOf(c);
    return kind !== undefined && achieved.has(kind);
  });
  const plain = row.cells.filter((c) => bonusKindOf(c) === undefined);
  const fill = plain.slice(0, Math.max(0, target - required.length));
  return [...required, ...fill].map((c) => c.id);
}

/**
 * Crosses each colour row and marks the strip. Returns the crosses actually made, so the score card
 * is built from the board rather than alongside it — the two cannot drift apart.
 */
function buildProgress(layout: SheetLayout, p: PreviewPlayer) {
  const achieved = achievedKinds(p);
  const rowStates: Record<string, { crossedCells: string[]; lockCrossed: boolean }> = {};
  const crosses: Record<string, number> = {};

  for (const row of layout.rows) {
    if (!isScoringColourRow(row)) continue;
    const colour = row.cells[0]?.color;
    if (!colour) continue;
    const crossed = chooseCrosses(row, p.crosses[colour] ?? 0, achieved);
    rowStates[row.id] = { crossedCells: crossed, lockCrossed: false };
    crosses[colour] = crossed.length;
  }

  markStrip(layout, achieved, rowStates);
  return { progress: { rowStates, punishments: p.punishments }, crosses };
}

/** Marks the strip indicators for the bonuses this player achieved, as completing a pair would. */
function markStrip(
  layout: SheetLayout,
  achieved: Set<string>,
  rowStates: Record<string, { crossedCells: string[]; lockCrossed: boolean }>,
): void {
  const strip: SheetRow | undefined = layout.rows.find((r) => r.bonusBStrip);
  if (!strip) return;

  rowStates[strip.id] = {
    crossedCells: strip.cells
      .filter((c) => {
        const kind = bonusKindOf(c);
        return kind !== undefined && achieved.has(kind);
      })
      .map((c) => c.id),
    lockCrossed: false,
  };
}

/**
 * Builds a finished game for `playerCount` players on the given layout. Returns the same shapes the
 * score screen would otherwise fetch, so it runs the real animation with no preview-only branches.
 */
export function buildPreviewGame(
  layout: SheetLayout,
  playerCount: number,
): { state: GameState; scores: Record<string, ScoreCard> } {
  const cast = CAST.slice(0, Math.min(playerCount, CAST.length));
  const players = cast.map((_, i) => ({ id: `preview-${i}`, name: NAMES[i] }));

  const sheetLayouts: Record<string, SheetLayout> = {};
  const sheetProgress: Record<string, unknown> = {};
  const scores: Record<string, ScoreCard> = {};

  cast.forEach((p, i) => {
    const id = players[i]!.id; // players is built from cast, so the indices line up
    const { progress, crosses } = buildProgress(layout, p);
    sheetLayouts[id] = layout;
    sheetProgress[id] = progress;
    scores[id] = buildScoreCard(p, crosses);
  });

  return { state: { players, sheetLayouts, sheetProgress, closedRows: {} } as unknown as GameState, scores };
}

/** The id of the player whose board is shown beneath the table. */
export const PREVIEW_PLAYER_ID = 'preview-0';
