import { test, expect, Page } from '@playwright/test';

// ── Device: OnePlus Nord / Pixel 6 class (portrait) ───────────────────────────
const PHONE_W = 412;
const PHONE_H = 915;

const SESSION_ID = 'session-mobile-test';
const PLAYER_ID = 'player-1';
const OTHER_ID = 'player-2';

// ── Realistic Qwixx board ─────────────────────────────────────────────────────
function makeRows() {
  return [
    { color: 'RED', ascending: true },
    { color: 'YELLOW', ascending: true },
    { color: 'GREEN', ascending: false },
    { color: 'BLUE', ascending: false },
  ].map(({ color, ascending }) => {
    const values = Array.from({ length: 11 }, (_, i) => (ascending ? i + 2 : 12 - i));
    const cells = values.map((v, i) => ({
      id: `${color}-${v}`,
      position: i,
      displayValue: String(v),
      color,
      closingEligible: i === 10,
      tags: [],
    }));
    const lockVal = ascending ? 12 : 2;
    return {
      id: `row-${color}`,
      cells,
      lock: {
        id: `lock-${color}`,
        color,
        minCrosses: 5,
        closingCells: [`${color}-${lockVal}`],
      },
    };
  });
}

const ROWS = makeRows();

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
    currentRoll: {
      white1: 3,
      white2: 4,
      coloredDice: { RED: 2, YELLOW: 3, GREEN: 4, BLUE: 5 },
    },
    whiteWhiteUsed: false,
    colorDieUsed: false,
  },
  gameOver: false,
  version: 1,
};

// ── Helpers ───────────────────────────────────────────────────────────────────

async function setup(page: Page) {
  // Bypass the first-visit rules redirect so we land directly on the board.
  await page.context().addCookies([{ name: 'qwixx_rules_seen_v1', value: '1', domain: 'localhost', path: '/' }]);
  await page.route(`**/gamestates/${SESSION_ID}**`, (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify(MOCK_STATE) }),
  );
  await page.route(`**/moves/**`, (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ result: 'ACCEPTED' }),
    }),
  );
}

/** Assert an element's visual bounding box stays within the device viewport. */
async function expectInViewport(page: Page, selector: string) {
  const box = await page.locator(selector).boundingBox();
  expect(box, `${selector} has no bounding box`).not.toBeNull();
  expect(box!.x, `${selector} overflows left`).toBeGreaterThanOrEqual(-1);
  expect(box!.y, `${selector} overflows top`).toBeGreaterThanOrEqual(-1);
  expect(box!.x + box!.width, `${selector} overflows right`).toBeLessThanOrEqual(PHONE_W + 1);
  expect(box!.y + box!.height, `${selector} overflows bottom`).toBeLessThanOrEqual(PHONE_H + 1);
}

// ── Tests ─────────────────────────────────────────────────────────────────────

test.describe('Board layout on portrait phone (OnePlus Nord / Pixel 6 class)', () => {
  test.use({ viewport: { width: PHONE_W, height: PHONE_H } });

  test.beforeEach(async ({ page }) => {
    await setup(page);
    await page.goto(`/game/${SESSION_ID}/${PLAYER_ID}`);
    await page.waitForSelector('app-row', { timeout: 10_000 });
  });

  test('all 4 game rows are rendered', async ({ page }) => {
    await expect(page.locator('app-row')).toHaveCount(4);
  });

  test('all 4 rows are visible without scrolling', async ({ page }) => {
    const rows = page.locator('app-row');
    for (let i = 0; i < 4; i++) {
      await expect(rows.nth(i)).toBeVisible();
      await expectInViewport(page, `app-row:nth-child(${i + 1})`);
    }
  });

  test('all rows are the same width (aligned layout)', async ({ page }) => {
    const rows = page.locator('app-row');
    const widths = await Promise.all(
      Array.from({ length: 4 }, (_, i) =>
        rows
          .nth(i)
          .boundingBox()
          .then((b) => b!.width),
      ),
    );
    // All four rows should be the same width (aligned layout): total spread under 2px.
    expect(Math.max(...widths) - Math.min(...widths)).toBeLessThan(2);
  });

  test('turn bar is visible and fully within viewport', async ({ page }) => {
    await expect(page.locator('.turn-bar')).toBeVisible();
    await expectInViewport(page, '.turn-bar');
  });

  test('dice area is visible', async ({ page }) => {
    await expect(page.locator('.dice-area')).toBeVisible();
    await expectInViewport(page, '.dice-area');
  });

  test('pass button is visible for passive player', async ({ page }) => {
    // Current player (PLAYER_ID) is in passive queue during ACTIVE_MOVE
    await expect(page.locator('button.btn-pass-arrow')).toBeVisible();
    await expectInViewport(page, 'button.btn-pass-arrow');
  });

  test('punishment track is visible and within viewport', async ({ page }) => {
    await expect(page.locator('.punishment-track')).toBeVisible();
    await expectInViewport(page, '.punishment-track');
  });

  test('score strip is visible and within viewport', async ({ page }) => {
    await expect(page.locator('.score-strip')).toBeVisible();
    await expectInViewport(page, '.score-strip');
  });

  test('board is rotated to landscape orientation (CSS applied)', async ({ page }) => {
    const host = await page.locator('app-board').boundingBox();
    expect(host).not.toBeNull();
    // After rotation: visual width should match PHONE_W, visual height PHONE_H
    // The rotated element fills the portrait screen
    expect(host!.width).toBeCloseTo(PHONE_W, 0);
    expect(host!.height).toBeCloseTo(PHONE_H, 0);
  });

  test('no row cell text is clipped (cells have positive dimensions)', async ({ page }) => {
    const cells = page.locator('app-cell .cell-value');
    const count = await cells.count();
    expect(count).toBeGreaterThanOrEqual(44); // 4 rows × 11 cells
    for (let i = 0; i < count; i++) {
      const box = await cells.nth(i).boundingBox();
      expect(box!.width).toBeGreaterThan(0);
      expect(box!.height).toBeGreaterThan(0);
    }
  });
});

// ── Transition test ───────────────────────────────────────────────────────────

test.describe('Board layout after active→passive state transition', () => {
  test.use({ viewport: { width: PHONE_W, height: PHONE_H } });

  test('all elements stay within viewport after player transitions from active to passive', async ({ page }) => {
    // Phase 1: PLAYER_ID is active, waiting to roll (no dice yet)
    const rollPhaseState = {
      ...MOCK_STATE,
      version: 10,
      turnState: {
        activePlayerId: PLAYER_ID,
        phase: 'ROLL',
        passivePlayerQueue: [OTHER_ID],
        currentRoll: null,
        whiteWhiteUsed: false,
        colorDieUsed: false,
      },
    };

    // Phase 2: PLAYER_ID rolled the dice and is in ACTIVE_MOVE
    const activeMoveState = {
      ...MOCK_STATE,
      version: 11,
      turnState: {
        activePlayerId: PLAYER_ID,
        phase: 'ACTIVE_MOVE',
        passivePlayerQueue: [OTHER_ID],
        currentRoll: {
          white1: 3,
          white2: 4,
          coloredDice: { RED: 2, YELLOW: 3, GREEN: 4, BLUE: 5 },
        },
        whiteWhiteUsed: false,
        colorDieUsed: false,
      },
    };

    // Phase 3: PLAYER_ID passed; OTHER_ID is now the active player in ROLL phase
    // (no dice yet) — this is the exact scenario that caused the layout cut-off:
    // the dice area disappeared, the board's natural width shrank from ~1159px to
    // ~1059px, and --mobile-scale jumped from 0.76 to 0.83.
    const passiveWaitState = {
      ...MOCK_STATE,
      version: 12,
      turnState: {
        activePlayerId: OTHER_ID,
        phase: 'ROLL',
        passivePlayerQueue: [PLAYER_ID],
        currentRoll: null,
        whiteWhiteUsed: false,
        colorDieUsed: false,
      },
    };

    let currentState: object = rollPhaseState;

    // Initial fetch (GET /gamestates/:id) returns the current phase as JSON.
    await page.route(`**/gamestates/${SESSION_ID}**`, (route) =>
      route.fulfill({ contentType: 'application/json', body: JSON.stringify(currentState) }),
    );
    // The board is SSE-driven: it only updates via the /stream EventSource. Serve it as a real
    // text/event-stream carrying the current phase. The board reconnects ~every 3s, so each phase
    // change propagates on the next reconnect. Registered after the JSON route so it wins for /stream.
    await page.route(`**/gamestates/${SESSION_ID}/stream`, (route) =>
      route.fulfill({ contentType: 'text/event-stream', body: `data: ${JSON.stringify(currentState)}\n\n` }),
    );
    await page.route(`**/moves/**`, (route) =>
      route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ result: 'ACCEPTED' }),
      }),
    );

    // ── Step 1: load page as active player in ROLL phase ──────────────────────
    await page.context().addCookies([{ name: 'qwixx_rules_seen_v1', value: '1', domain: 'localhost', path: '/' }]);
    await page.goto(`/game/${SESSION_ID}/${PLAYER_ID}`);
    await page.waitForSelector('app-row', { timeout: 10_000 });

    // Roll button must be present and all rows in view
    await expect(page.locator('button.btn-roll')).toBeVisible();
    await expectInViewport(page, '.turn-bar');
    await expectInViewport(page, 'app-row:nth-child(1)');
    await expectInViewport(page, 'app-row:nth-child(4)');

    // ── Step 2: simulate rolling (state gains a roll + action buttons) ─────────
    currentState = activeMoveState;
    // Wait for the SSE reconnect to deliver the new phase: the roll button disappears
    // once the active player has rolled (phase ROLL → ACTIVE_MOVE).
    await expect(page.locator('button.btn-roll')).toBeHidden({ timeout: 8000 });

    await expectInViewport(page, '.turn-bar');
    await expectInViewport(page, '.dice-area');
    await expectInViewport(page, 'app-row:nth-child(1)');
    await expectInViewport(page, 'app-row:nth-child(4)');
    await expectInViewport(page, '.score-strip');

    const scaleDuringActiveMovePhase = await page
      .locator('app-board')
      .evaluate((el: HTMLElement) => parseFloat(getComputedStyle(el).getPropertyValue('--mobile-scale') || '0'));

    // ── Step 3: turn ends → PLAYER_ID is now passive, OTHER_ID in ROLL phase ──
    currentState = passiveWaitState;

    // Wait for the SSE reconnect to deliver the new phase: the turn bar now names the
    // other player ("Bob's turn") instead of "Your turn".
    await expect(page.locator('.turn-bar')).toContainText('Bob', { timeout: 8000 });

    // All board elements must be fully within the phone viewport.
    await expectInViewport(page, '.turn-bar');
    await expectInViewport(page, '.dice-area');
    await expectInViewport(page, 'app-row:nth-child(1)');
    await expectInViewport(page, 'app-row:nth-child(4)');
    await expectInViewport(page, '.score-strip');
    await expectInViewport(page, '.punishment-track');

    // --mobile-scale must be set to a valid fraction.
    const scaleAfter = await page
      .locator('app-board')
      .evaluate((el: HTMLElement) => parseFloat(getComputedStyle(el).getPropertyValue('--mobile-scale') || '0'));
    expect(scaleAfter, '--mobile-scale should be > 0 after transition').toBeGreaterThan(0);
    expect(scaleAfter, '--mobile-scale should be ≤ 1').toBeLessThanOrEqual(1);

    // The scale must not change significantly between active-move and passive-wait phases.
    // A large swing (e.g. 0.76 → 0.83) means the dice area disappeared and changed the
    // board's natural width — this was the root cause of the layout cut-off.
    expect(
      Math.abs(scaleAfter - scaleDuringActiveMovePhase),
      `scale jumped from ${scaleDuringActiveMovePhase} to ${scaleAfter}`,
    ).toBeLessThan(0.02);
  });
});
