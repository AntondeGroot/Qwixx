import { test, expect, Page, devices } from '@playwright/test';

/**
 * Regression: the X-Change row fell off the bottom of the screen on a phone held
 * horizontally.
 *
 * Two things have to be true at once for this to bite, which is why it survived so long:
 *
 *  1. A wide sheet — Longo (15 cells per row) plus the X-Change row appended last.
 *  2. A *landscape* viewport, where `app-board` is a normal block and `.board-layout`
 *     is therefore width-constrained by `max-width: 100%`.
 *
 * Two independent defects in `applyMobileScale()` stacked up, and both had to be fixed:
 *
 *  a) It measured the board at zoom 1, where the squeeze applied — but the zoom it then
 *     applied changed how much width `max-width: 100%` granted, which changed the height,
 *     which invalidated the measurement it was derived from. `min-width: max-content` on
 *     `.board-layout` (now unconditional, not portrait-only) breaks that circularity.
 *  b) It scaled against `window.innerWidth/innerHeight` — the 100vw/100vh box, which
 *     includes browser chrome and the scrollbar gutter — while the CSS lays the board out
 *     in the 100dvw/100dvh box. On an emulated Pixel 7 those differ by 50x21px.
 *
 * Either way the zoom came out too large and the bottom of the sheet hung below the fold.
 * The X-Change row is appended last (ConfigurableGameStyleFactory#buildXChangeRow), so it
 * is the first thing to disappear — which is exactly how this was reported.
 *
 * Emulating the device matters: a bare `setViewportSize({width: 844, height: 390})` does
 * NOT reproduce this. The real descriptors carry `isMobile`, the device pixel ratio and a
 * viewport with the browser's own chrome already subtracted (iPhone 14 landscape is
 * 750x340, not 844x390), and it is that combination that pushes the board over the edge.
 */

const SESSION_ID = 'session-landscape-xchange';
const PLAYER_ID = 'player-1';
const OTHER_ID = 'player-2';

// Longo rows: 15 cells (2–16), locks needing 7 crosses. Values mirror the server's
// buildAscendingRow/buildDescendingRow for BASE=LONGO.
function longoColourRows() {
  return [
    { color: 'RED', ascending: true },
    { color: 'YELLOW', ascending: true },
    { color: 'GREEN', ascending: false },
    { color: 'BLUE', ascending: false },
  ].map(({ color, ascending }) => {
    const values = Array.from({ length: 15 }, (_, i) => (ascending ? i + 2 : 16 - i));
    const cells = values.map((v, i) => ({
      id: `${color}-${v}`,
      position: i,
      displayValue: String(v),
      color,
      closingEligible: i >= 13,
      tags: [],
    }));
    return {
      id: `row-${color}`,
      cells,
      lock: { id: `lock-${color}`, color, minCrosses: 7, closingCells: [cells[13]!.id, cells[14]!.id] },
    };
  });
}

// X_CHANGE_PAIRS_LONGO from ConfigurableGameStyleFactory — 11 cells, no lock, appended last.
const X_CHANGE_PAIRS_LONGO = [
  [11, 6],
  [12, 9],
  [14, 4],
  [10, 7],
  [9, 5],
  [13, 4],
  [11, 7],
  [13, 6],
  [12, 8],
  [14, 12],
  [7, 5],
];

function xChangeRow() {
  return {
    id: 'row-xchange',
    cells: X_CHANGE_PAIRS_LONGO.map(([a, b], i) => ({
      id: `xc-${i}`,
      position: i,
      displayValue: '',
      color: 'BLUE',
      closingEligible: false,
      tags: [{ type: 'X_CHANGE', valueA: a, valueB: b }],
    })),
  };
}

const ROWS = [...longoColourRows(), xChangeRow()];

const MOCK_STATE = {
  players: [
    { id: PLAYER_ID, name: 'Alice' },
    { id: OTHER_ID, name: 'Bob' },
  ],
  sheetLayouts: { [PLAYER_ID]: { rows: ROWS }, [OTHER_ID]: { rows: ROWS } },
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

async function setup(page: Page) {
  // Bypass the first-visit rules redirect so we land directly on the board.
  await page.context().addCookies([{ name: 'qwixx_rules_seen_v1', value: '1', domain: 'localhost', path: '/' }]);
  await page.route(`**/gamestates/${SESSION_ID}**`, (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify(MOCK_STATE) }),
  );
  await page.route(`**/moves/**`, (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ result: 'ACCEPTED' }) }),
  );
}

/**
 * Assert an element's visual bounding box stays within the device viewport.
 *
 * Deliberately measured with documentElement.clientWidth/clientHeight, not
 * window.innerWidth/innerHeight. The latter is the 100vw/100vh box, which includes the
 * browser's chrome and scrollbar gutter — on an emulated Pixel 7 held horizontally it is
 * 913x381 where the visible area is only 863x360. Asserting against innerHeight would let
 * 21px of board hang off the bottom and still call the test green.
 */
async function expectInViewport(page: Page, selector: string) {
  const viewport = await page.evaluate(() => ({
    w: document.documentElement.clientWidth,
    h: document.documentElement.clientHeight,
  }));
  const box = await page.locator(selector).boundingBox();
  expect(box, `${selector} has no bounding box`).not.toBeNull();
  expect(box!.x, `${selector} overflows left`).toBeGreaterThanOrEqual(-1);
  expect(box!.y, `${selector} overflows top`).toBeGreaterThanOrEqual(-1);
  expect(box!.x + box!.width, `${selector} overflows right`).toBeLessThanOrEqual(viewport.w + 1);
  expect(box!.y + box!.height, `${selector} overflows bottom`).toBeLessThanOrEqual(viewport.h + 1);
}

// Phones held horizontally. `defaultBrowserType` is stripped because Playwright rejects it
// inside a describe group; the project already pins chromium.
const PHONES_HELD_HORIZONTALLY = ['iPhone 14 landscape', 'Pixel 7 landscape', 'Galaxy S9+ landscape'];

for (const name of PHONES_HELD_HORIZONTALLY) {
  const { defaultBrowserType: _unused, ...device } = devices[name]!;

  test.describe(`Longo + X-Change on ${name}`, () => {
    test.use(device);

    test.beforeEach(async ({ page }) => {
      await setup(page);
      await page.goto(`/game/${SESSION_ID}/${PLAYER_ID}`);
      await page.waitForSelector('.xchange-row', { timeout: 10_000 });
    });

    test('x change row stays within viewport when phone held horizontally', async ({ page }) => {
      await expect(page.locator('.xchange-row')).toBeVisible();
      await expectInViewport(page, '.xchange-row');
    });

    // The score strip and penalty track sit below the X-Change row, so they go over the
    // edge first and by the largest margin. Pinning them holds the whole bottom edge of
    // the sheet, not just the one row that happened to get reported.
    test('score strip and penalty track stay within viewport when phone held horizontally', async ({ page }) => {
      await expectInViewport(page, '.score-strip');
      await expectInViewport(page, '.punishment-track');
    });

    // Guards the mechanism behind the fix rather than its symptom. applyMobileScale()
    // measures .board-layout at zoom 1 and derives the zoom from that measurement, so the
    // measurement has to be truthful: the box it reports must actually contain the board.
    // Before the fix max-width:100% squeezed the grid to 718px while its content needed
    // 974px, and the 256px hanging outside the measured box became the cut-off sheet.
    test('board is not squeezed narrower than its content when measured', async ({ page }) => {
      const measured = await page.evaluate(() => {
        const host = document.querySelector('app-board') as HTMLElement;
        const layout = document.querySelector('.board-layout') as HTMLElement;
        const applied = getComputedStyle(host).getPropertyValue('--mobile-scale');

        // Reproduce exactly what applyMobileScale() does before it reads the board.
        host.style.setProperty('--mobile-scale', '1');
        const atZoom1 = { offsetW: layout.offsetWidth, scrollW: layout.scrollWidth };
        host.style.setProperty('--mobile-scale', applied);
        const rendered = { offsetW: layout.offsetWidth };

        return { applied, atZoom1, rendered };
      });

      expect(parseFloat(measured.applied), 'board should be scaled down to fit').toBeLessThan(1);

      // The measured box must contain the content — no overflow hiding outside offsetWidth.
      expect(
        measured.atZoom1.scrollW,
        `content (${measured.atZoom1.scrollW}px) overflows the measured box (${measured.atZoom1.offsetW}px)`,
      ).toBeLessThanOrEqual(measured.atZoom1.offsetW + 1);

      // And measuring must not itself change the board's width: offsetWidth is an integer
      // and CSS zoom rounds sub-pixel layout slightly differently, so allow a few px. The
      // regression this guards was a 256px gap, far outside this tolerance.
      const ROUNDING_TOLERANCE_PX = 4;
      expect(
        Math.abs(measured.atZoom1.offsetW - measured.rendered.offsetW),
        `measured width ${measured.atZoom1.offsetW} must match rendered width ${measured.rendered.offsetW}`,
      ).toBeLessThanOrEqual(ROUNDING_TOLERANCE_PX);
    });
  });
}
