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
    defaultValue: 'SAME_CARDS',
    choices: ['SAME_CARDS', 'DIFFERENT_CARDS'],
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
  await page.route('**/game-options/preview**', (route) =>
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

/** The botCount pill group's buttons (the #botCount radiogroup renders one .int-pill per value). */
function botCountPills(page: Parameters<typeof test>[1]['page']) {
  return page.locator('#botCount .int-pill');
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

  test('botCount has pills 0, 1, 2, 3 even with chips and descriptions rendered', async ({ page }) => {
    const pills = botCountPills(page);
    await expect(pills).toHaveCount(4);
    const texts = await pills.allTextContents();
    expect(texts.map((t) => t.trim())).toEqual(['0', '1', '2', '3']);
  });

  test('botCount pills are visible on screen', async ({ page }) => {
    await expect(botCountPills(page).first()).toBeVisible();
  });

  test('botCount default is 0 (first pill selected)', async ({ page }) => {
    const pills = botCountPills(page);
    await expect(pills.nth(0)).toHaveClass(/selected/);
    await expect(pills.nth(1)).not.toHaveClass(/selected/);
  });

  test('botCount responds to selection', async ({ page }) => {
    const pills = botCountPills(page);
    await pills.nth(3).click();
    await expect(pills.nth(3)).toHaveClass(/selected/);
  });

  test('submit button is hidden in embed mode', async ({ page }) => {
    await expect(page.locator('button[type="submit"]')).toBeHidden();
  });

  test('botCount pills survive after a re-render triggered by form change', async ({ page }) => {
    // Changing another option triggers Angular change detection — botCount must survive.
    await page.locator('select').first().selectOption({ index: 1 });
    await page.waitForTimeout(300); // let debounced fetch fire

    const pills = botCountPills(page);
    await expect(pills).toHaveCount(4);
    const texts = await pills.allTextContents();
    expect(texts.map((t) => t.trim())).toEqual(['0', '1', '2', '3']);
  });
});
