/**
 * CSS structure tests for the mobile landscape-rotation pattern.
 *
 * The orientation lock (`transform: rotate(90deg)` in portrait) now lives on
 * `app-root:has(app-board)` in the global `styles.css` rather than on the
 * individual component `:host` elements.  When a CSS `transform` is applied to
 * an ancestor, that ancestor becomes the containing block for all
 * `position:fixed` descendants (CSS spec §9.3).  The lock-intent modal's
 * `.modal-overlay` therefore anchors to the rotated `app-root` viewport and
 * stays within bounds — no portrait override in the component stylesheet is
 * needed.
 *
 * These tests read the raw CSS files and assert the structural rules that
 * prevent the mobile layout from regressing, acting as a regression guard
 * without needing a real browser or visual comparison.
 *
 * dvh / dvw units
 * ───────────────
 * The rotation trick positions `app-root` with  width: 100dvh; height: 100dvw.
 * Using plain  vh / vw  instead causes the right edge of the rotated landscape
 * view to be hidden under the browser's navigation bar, because 100vh includes
 * the navigation-bar area on Android whereas 100dvh is the *dynamic* (usable)
 * viewport height that excludes browser chrome.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const dir = dirname(fileURLToPath(import.meta.url));
function css(rel: string) {
  return readFileSync(join(dir, rel), 'utf-8');
}

// ── Helpers ────────────────────────────────────────────────────────────────

/**
 * Returns true if any @media (orientation: portrait) block in cssText
 * contains `selector { ... prop ... value ... }`.
 *
 * Handles both `@media (orientation: portrait)` and
 * `@media screen and (orientation: portrait)` syntax.
 */
function portraitBlockHas(cssText: string, selector: string, prop: string, value: string): boolean {
  const portraitBlocks =
    cssText.match(
      /@media[^(]*\([^)]*orientation:\s*portrait[^)]*\)\s*\{([\s\S]*?)(?=@media\s|\s*$)/g,
    ) ?? [];
  for (const block of portraitBlocks) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(escaped + '\\s*\\{([^}]*)\\}', 'g');
    let m: RegExpExecArray | null;
    while ((m = re.exec(block)) !== null) {
      if (m[1].includes(prop) && m[1].includes(value)) return true;
    }
  }
  return false;
}

/**
 * Extracts the value of a CSS property from a selector inside a portrait @media block.
 *
 * Handles both `@media (orientation: portrait)` and
 * `@media screen and (orientation: portrait)` syntax.
 */
function getPortraitPropertyValue(cssText: string, selector: string, prop: string): string | null {
  const portraitBlocks =
    cssText.match(
      /@media[^(]*\([^)]*orientation:\s*portrait[^)]*\)\s*\{([\s\S]*?)(?=@media\s|\s*$)/g,
    ) ?? [];
  for (const block of portraitBlocks) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const re = new RegExp(escaped + '\\s*\\{([^}]*)\\}', 'g');
    let m: RegExpExecArray | null;
    while ((m = re.exec(block)) !== null) {
      const propRe = new RegExp(prop + '\\s*:\\s*([^;]+)', 'i');
      const pm = propRe.exec(m[1]);
      if (pm) return pm[1].trim();
    }
  }
  return null;
}

function globalSelectorHas(
  cssText: string,
  selector: string,
  prop: string,
  value: string,
): boolean {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(escaped + '\\s*\\{([^}]*)\\}', 'g');
  let m: RegExpExecArray | null;
  while ((m = re.exec(cssText)) !== null) {
    if (m[1].includes(prop) && m[1].includes(value)) return true;
  }
  return false;
}

// ── Row-closure modal ──────────────────────────────────────────────────────

describe('row-closure-modal — mobile CSS structure', () => {
  const modalCss = css('row-closure-modal/row-closure-modal.component.css');

  it('.modal-overlay uses position:fixed in the default (landscape/desktop) rule', () => {
    expect(globalSelectorHas(modalCss, '.modal-overlay', 'position', 'fixed')).toBe(true);
  });

  // The old architecture rotated the modal :host in portrait and switched
  // .modal-overlay to position:absolute so it anchored to the rotated host.
  // The new architecture rotates app-root instead: position:fixed on
  // .modal-overlay now anchors to the transformed app-root, so the modal fills
  // the rotated viewport correctly — no portrait override is needed or wanted.
  // Adding position:absolute back would break the modal on mobile.
  it('.modal-overlay does NOT override to position:absolute in portrait (fixed anchors to rotated app-root)', () => {
    expect(portraitBlockHas(modalCss, '.modal-overlay', 'position', 'absolute')).toBe(false);
  });

  // Regression guard: if someone adds :host portrait rotation back, the modal
  // would be double-rotated (once by app-root, once by :host itself).
  it(':host does NOT have portrait rotation (rotation is on app-root in styles.css)', () => {
    expect(portraitBlockHas(modalCss, ':host', 'transform', 'rotate(90deg)')).toBe(false);
  });
});

// ── Score component ────────────────────────────────────────────────────────

describe('score component — mobile CSS structure', () => {
  const scoreCss = css('score/score.component.css');

  // Score screen is no longer rotated on mobile — it renders normally in portrait
  // and shows the player's final board (scaled to fit) below the score table.
  it(':host is NOT rotated in portrait mode', () => {
    expect(portraitBlockHas(scoreCss, ':host', 'transform', 'rotate(90deg)')).toBe(false);
  });

  // Horizontal overflow prevention so white browser background doesn't show on right.
  it(':host has overflow-x:hidden in portrait mode', () => {
    expect(portraitBlockHas(scoreCss, ':host', 'overflow-x', 'hidden')).toBe(true);
  });

  // Bucket columns narrow enough to leave room for the player name on a 390 px screen.
  it('.bucket-cell is narrower in portrait mode (≤ 44px)', () => {
    const widthStr = getPortraitPropertyValue(scoreCss, '.bucket-cell', 'width');
    const px = parseFloat(widthStr ?? '56px');
    expect(px).toBeLessThanOrEqual(44);
  });

  // Total column must also be reduced so it fits within 390 px.
  it('.total-cell is narrower in portrait mode (≤ 56px)', () => {
    const widthStr = getPortraitPropertyValue(scoreCss, '.total-cell', 'width');
    const px = parseFloat(widthStr ?? '72px');
    expect(px).toBeLessThanOrEqual(56);
  });

  it('.modal-overlay uses position:fixed in the default rule', () => {
    expect(globalSelectorHas(scoreCss, '.modal-overlay', 'position', 'fixed')).toBe(true);
  });

  it('.final-board-outer has overflow:hidden to clip the scaled board', () => {
    expect(globalSelectorHas(scoreCss, '.final-board-outer', 'overflow', 'hidden')).toBe(true);
  });
});

// ── Board component ────────────────────────────────────────────────────────

describe('board component — mobile CSS structure', () => {
  const boardCss = css('board/board.component.css');
  // The orientation lock lives on app-root:has(app-board) in the global stylesheet,
  // not on the board component :host.
  const globalCss = css('../styles.css');

  it('app-root:has(app-board) is rotated 90deg in portrait mode (global stylesheet)', () => {
    expect(
      portraitBlockHas(globalCss, 'app-root:has(app-board)', 'transform', 'rotate(90deg)'),
    ).toBe(true);
  });

  it('app-root:has(app-board) has overflow:hidden in portrait mode to clip the rotated content', () => {
    expect(portraitBlockHas(globalCss, 'app-root:has(app-board)', 'overflow', 'hidden')).toBe(true);
  });

  // Regression: 100vh includes the Android navigation-bar area; 100dvh is the
  // usable height only. Using vh causes the right edge of the rotated landscape
  // view to be hidden behind the navigation bar.
  it('app-root:has(app-board) uses 100dvh for width (not plain vh) so the nav bar is excluded', () => {
    expect(portraitBlockHas(globalCss, 'app-root:has(app-board)', 'width', '100dvh')).toBe(true);
  });

  it('app-root:has(app-board) uses 100dvw for height (not plain vw) so the nav bar is excluded', () => {
    expect(portraitBlockHas(globalCss, 'app-root:has(app-board)', 'height', '100dvw')).toBe(true);
  });

  // Regression guard: board :host must NOT have its own portrait rotation.
  // Rotation is on app-root in styles.css; adding it here would double-rotate the board.
  it('board :host does NOT have portrait rotation (rotation is on app-root in styles.css)', () => {
    expect(portraitBlockHas(boardCss, ':host', 'transform', 'rotate(90deg)')).toBe(false);
  });
});
