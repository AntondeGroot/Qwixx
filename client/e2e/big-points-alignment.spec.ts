import { test, expect } from '@playwright/test';

const SESSION_ID = 'session-bigpoints-alignment';
const PLAYER_ID = 'player-1';
const OTHER_ID = 'player-2';

function makeRegularCells(color: string, ascending: boolean) {
  return Array.from({ length: 11 }, (_, i) => ({
    id: `${color}-${i}`,
    position: i,
    displayValue: String(ascending ? i + 2 : 12 - i),
    color,
    closingEligible: i === 10,
    tags: [],
  }));
}

function makeBonusCells(primaryColor: string, secondaryColor: string, ascending: boolean) {
  return Array.from({ length: 11 }, (_, i) => ({
    id: `bonus-${primaryColor}-${i}`,
    position: i,
    displayValue: String(ascending ? i + 2 : 12 - i),
    color: primaryColor,
    closingEligible: false,
    tags: [{ type: 'SECONDARY_COLOR', secondaryColor }],
  }));
}

function lock(color: string) {
  return { id: `lock-${color}`, color, minCrosses: 5, closingCells: [] };
}

const ROWS = [
  { id: 'row-RED', cells: makeRegularCells('RED', true), lock: lock('RED') },
  { id: 'row-BONUS-RY', cells: makeBonusCells('RED', 'YELLOW', true), lock: null },
  { id: 'row-YELLOW', cells: makeRegularCells('YELLOW', true), lock: lock('YELLOW') },
  { id: 'row-GREEN', cells: makeRegularCells('GREEN', false), lock: lock('GREEN') },
  { id: 'row-BONUS-GB', cells: makeBonusCells('GREEN', 'BLUE', false), lock: null },
  { id: 'row-BLUE', cells: makeRegularCells('BLUE', false), lock: lock('BLUE') },
];

const MOCK_STATE = {
  players: [
    { id: PLAYER_ID, name: 'Alice' },
    { id: OTHER_ID, name: 'Bob' },
  ],
  sheetLayouts: {
    [PLAYER_ID]: { rows: ROWS },
    [OTHER_ID]: { rows: ROWS },
  },
  sheetProgress: {
    [PLAYER_ID]: { punishments: 0, rowStates: {} },
    [OTHER_ID]: { punishments: 0, rowStates: {} },
  },
  closedRows: {},
  activeDiceColors: ['RED', 'YELLOW', 'GREEN', 'BLUE'],
  turnState: {
    activePlayerId: OTHER_ID,
    phase: 'ACTIVE_MOVE',
    passivePlayerQueue: [PLAYER_ID],
    currentRoll: { white1: 3, white2: 4, coloredDice: { RED: 2, YELLOW: 3, GREEN: 4, BLUE: 5 } },
    whiteWhiteUsed: false,
    colorDieUsed: false,
  },
  gameOver: false,
  version: 1,
};

test.describe('Big Points bonus row alignment', () => {
  test.beforeEach(async ({ page }) => {
    // Bypass the first-visit rules redirect so we land directly on the board.
    await page.context().addCookies([
      {
        name: 'qwixx_rules_seen_v1',
        value: '1',
        domain: 'localhost',
        path: '/',
      },
    ]);
    await page.route(`**/gamestates/${SESSION_ID}**`, (route) =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify(MOCK_STATE) }),
    );
    await page.route(`**/moves/**`, (route) =>
      route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ result: 'ACCEPTED' }),
      }),
    );
    await page.goto(`/game/${SESSION_ID}/${PLAYER_ID}`);
    await page.waitForSelector('app-row', { timeout: 10_000 });
  });

  test('renders 6 rows (4 regular + 2 bonus)', async ({ page }) => {
    await expect(page.locator('app-row')).toHaveCount(6);
  });

  // For each position 0–10, the horizontal center of the big-points circle
  // must be within 2 px of the regular cell directly above it in the RED row.
  test('BONUS-RY circles are horizontally centred on the RED cells above', async ({ page }) => {
    const redRow = page.locator('app-row').nth(0);
    const bonusRow = page.locator('app-row').nth(1);

    for (let i = 0; i < 11; i++) {
      const redBox = await redRow.locator('app-cell').nth(i).locator('.cell').boundingBox();
      const bonusBox = await bonusRow.locator('app-cell').nth(i).locator('.cell').boundingBox();

      expect(redBox, `RED cell ${i} missing`).not.toBeNull();
      expect(bonusBox, `BONUS-RY cell ${i} missing`).not.toBeNull();

      const redCenter = redBox!.x + redBox!.width / 2;
      const bonusCenter = bonusBox!.x + bonusBox!.width / 2;

      expect(
        Math.abs(redCenter - bonusCenter),
        `position ${i}: RED x-center=${redCenter.toFixed(1)}, bonus x-center=${bonusCenter.toFixed(1)}`,
      ).toBeLessThan(2);
    }
  });

  test('BONUS-GB circles are horizontally centred on the GREEN cells above', async ({ page }) => {
    const greenRow = page.locator('app-row').nth(3);
    const bonusRow = page.locator('app-row').nth(4);

    for (let i = 0; i < 11; i++) {
      const greenBox = await greenRow.locator('app-cell').nth(i).locator('.cell').boundingBox();
      const bonusBox = await bonusRow.locator('app-cell').nth(i).locator('.cell').boundingBox();

      expect(greenBox, `GREEN cell ${i} missing`).not.toBeNull();
      expect(bonusBox, `BONUS-GB cell ${i} missing`).not.toBeNull();

      const greenCenter = greenBox!.x + greenBox!.width / 2;
      const bonusCenter = bonusBox!.x + bonusBox!.width / 2;

      expect(
        Math.abs(greenCenter - bonusCenter),
        `position ${i}: GREEN x-center=${greenCenter.toFixed(1)}, bonus x-center=${bonusCenter.toFixed(1)}`,
      ).toBeLessThan(2);
    }
  });
});
