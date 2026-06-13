import { test, expect } from '@playwright/test';

// Full set of game options as the GWT GameRoom would see them —
// including the options that get chips (luckyNumber, luckyCross, xChange, bigPoints,
// connectedCells, extraRow, randomOrder) and descriptions for all of them.
const GAME_OPTIONS = [
  {
    key: 'base',
    labelKey: 'gameOption.base',
    descriptionKey: 'gameOption.baseDescription',
    type: 'ENUM',
    defaultValue: 'STANDARD',
    choices: ['STANDARD', 'LONGO'],
    adminOnly: false,
    incompatibleWith: [],
  },
  {
    key: 'gameMode',
    labelKey: 'gameOption.gameMode',
    descriptionKey: 'gameOption.gameModeDescription',
    type: 'ENUM',
    defaultValue: 'ONLINE',
    choices: ['ONLINE', 'OFFLINE'],
    adminOnly: false,
    incompatibleWith: [],
  },
  {
    key: 'cardMode',
    labelKey: 'gameOption.cardMode',
    descriptionKey: 'gameOption.cardModeDescription',
    type: 'ENUM',
    defaultValue: 'DETERMINISTIC',
    choices: ['DETERMINISTIC', 'PROBABILISTIC'],
    adminOnly: false,
    incompatibleWith: [],
  },
  {
    key: 'bigPoints',
    labelKey: 'gameOption.bigPoints',
    descriptionKey: 'gameOption.bigPointsDescription',
    type: 'BOOLEAN',
    defaultValue: 'false',
    adminOnly: true,
    incompatibleWith: ['randomOrder'],
  },
  {
    key: 'randomOrder',
    labelKey: 'gameOption.randomOrder',
    descriptionKey: 'gameOption.randomOrderDescription',
    type: 'BOOLEAN',
    defaultValue: 'false',
    adminOnly: false,
    incompatibleWith: ['bigPoints'],
  },
  {
    key: 'extraRow',
    labelKey: 'gameOption.extraRow',
    descriptionKey: 'gameOption.extraRowDescription',
    type: 'BOOLEAN',
    defaultValue: 'false',
    adminOnly: false,
    incompatibleWith: [],
  },
  {
    key: 'connectedCells',
    labelKey: 'gameOption.connectedCells',
    descriptionKey: 'gameOption.connectedCellsDescription',
    type: 'BOOLEAN',
    defaultValue: 'false',
    adminOnly: false,
    incompatibleWith: [],
  },
  {
    key: 'xChange',
    labelKey: 'gameOption.xchange',
    descriptionKey: 'gameOption.xchangeDescription',
    type: 'BOOLEAN',
    defaultValue: 'false',
    adminOnly: true,
    incompatibleWith: [],
  },
  {
    key: 'luckyNumber',
    labelKey: 'gameOption.luckyNumber',
    descriptionKey: 'gameOption.luckyNumberDescription',
    type: 'BOOLEAN',
    defaultValue: 'false',
    adminOnly: true,
    incompatibleWith: [],
  },
  {
    key: 'luckyCross',
    labelKey: 'gameOption.luckyCross',
    descriptionKey: 'gameOption.luckyCrossDescription',
    type: 'BOOLEAN',
    defaultValue: 'false',
    adminOnly: true,
    incompatibleWith: ['bigPoints'],
  },
  {
    key: 'botCount',
    labelKey: 'gameOption.botCount',
    descriptionKey: 'gameOption.botCountDescription',
    type: 'INTEGER',
    defaultValue: '0',
    minValue: 0,
    maxValue: 3,
    adminOnly: false,
    incompatibleWith: [],
  },
  {
    key: 'botStrategy',
    labelKey: 'gameOption.botStrategy',
    descriptionKey: 'gameOption.botStrategyDescription',
    type: 'ENUM',
    defaultValue: 'BALANCED',
    choices: ['UNTRAINED', 'MOST_POINTS', 'MOST_WINS', 'BALANCED'],
    adminOnly: false,
    incompatibleWith: [],
  },
];

async function interceptOptions(page: Parameters<typeof test>[1]['page']) {
  await page.route('**/game-options', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify(GAME_OPTIONS) }),
  );
  await page.route('**/games/preview**', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify(null) }),
  );
}

/** Suppress the first-time-player redirect to /rules by setting the seen cookie. */
async function skipRulesRedirect(page: Parameters<typeof test>[1]['page']) {
  await page.goto('/');
  await page.evaluate(() => {
    document.cookie = 'qwixx_rules_seen_v1=1; path=/';
  });
}

/** Find the botCount select by looking for the <select> whose options are exactly "0","1","2","3". */
async function findBotCountSelect(page: Parameters<typeof test>[1]['page']) {
  const selects = page.locator('select');
  const count = await selects.count();
  for (let i = 0; i < count; i++) {
    const sel = selects.nth(i);
    const texts = await sel.locator('option').allTextContents();
    if (texts.length === 4 && texts.every((t, idx) => t.trim() === String(idx))) {
      return sel;
    }
  }
  return null;
}

// ── Full form with chips and descriptions (the real GWT embed scenario) ─────────

test.describe('Settings page — full form with chips and descriptions', () => {
  test.beforeEach(async ({ page }) => {
    await skipRulesRedirect(page);
    await interceptOptions(page);
    await page.goto('/settings?embed=1&lang=nl');
    await page.waitForSelector('form', { timeout: 10_000 });
  });

  test('renders option chips for visual options', async ({ page }) => {
    // At least some opt-chip elements should be visible (randomOrder, extraRow, etc.)
    const chips = page.locator('.opt-chip');
    const chipCount = await chips.count();
    expect(chipCount).toBeGreaterThan(0);
  });

  test('renders option descriptions', async ({ page }) => {
    // Every option with a descriptionKey gets a .option-desc paragraph.
    const descs = page.locator('.option-desc');
    const descCount = await descs.count();
    expect(descCount).toBeGreaterThan(0);
  });

  test('botCount select has options 0, 1, 2, 3 even with chips and descriptions rendered', async ({ page }) => {
    const sel = await findBotCountSelect(page);
    expect(sel).not.toBeNull();
    const texts = await sel!.locator('option').allTextContents();
    expect(texts.map((t) => t.trim())).toEqual(['0', '1', '2', '3']);
  });

  test('botCount select is visible on screen', async ({ page }) => {
    const sel = await findBotCountSelect(page);
    expect(sel).not.toBeNull();
    await expect(sel!).toBeVisible();
  });

  test('botCount select default is 0 (first option selected)', async ({ page }) => {
    const sel = await findBotCountSelect(page);
    expect(sel).not.toBeNull();
    const idx = await sel!.evaluate((el: HTMLSelectElement) => el.selectedIndex);
    const text = await sel!.evaluate((el: HTMLSelectElement) => el.options[el.selectedIndex]?.text.trim());
    expect(idx).toBe(0);
    expect(text).toBe('0');
  });

  test('botCount select responds to selection', async ({ page }) => {
    const sel = await findBotCountSelect(page);
    expect(sel).not.toBeNull();
    await sel!.selectOption({ index: 3 });
    const text = await sel!.evaluate((el: HTMLSelectElement) => el.options[el.selectedIndex]?.text.trim());
    expect(text).toBe('3');
  });

  test('submit button is hidden in embed mode', async ({ page }) => {
    await expect(page.locator('button[type="submit"]')).toBeHidden();
  });

  test('botCount options survive after a re-render triggered by form change', async ({ page }) => {
    // Changing another option triggers Angular change detection — botCount must survive.
    await page.locator('select').first().selectOption({ index: 1 });
    await page.waitForTimeout(300); // let debounced fetch fire

    const sel = await findBotCountSelect(page);
    expect(sel).not.toBeNull();
    const texts = await sel!.locator('option').allTextContents();
    expect(texts.map((t) => t.trim())).toEqual(['0', '1', '2', '3']);
  });
});
