/**
 * CSS structure tests for the mobile landscape-rotation pattern.
 *
 * Several components apply  transform: rotate(90deg)  to their :host in
 * portrait mode so the board reads in landscape orientation.  Any element
 * using  position: fixed  inside a CSS-transformed ancestor has its
 * containing block changed to that ancestor — the element ends up anchored
 * to the rotated element and appears off-screen or clipped on mobile.
 *
 * These tests read the raw CSS files and assert the structural rules that
 * prevent that bug, acting as a regression guard without needing a real
 * browser or visual comparison.
 *
 * dvh / dvw units
 * ───────────────
 * The rotation trick positions :host with  width: 100dvh; height: 100dvw.
 * Using plain  vh / vw  instead causes the right edge of the rotated
 * landscape view to be hidden under the browser's navigation bar, because
 * 100vh includes the navigation-bar area on Android whereas 100dvh is the
 * *dynamic* (usable) viewport height that excludes browser chrome.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// Anchor to the source tree via cwd (the client root when tests run), not import.meta.url — under
// v8 coverage instrumentation the module URL points elsewhere, which broke the relative reads.
const dir = join(process.cwd(), 'src/app');
function css(rel: string) {
  return readFileSync(join(dir, rel), 'utf-8');
}

// ── Helpers ────────────────────────────────────────────────────────────────

// Matches each `@media (orientation: portrait) { ... }` block. Only ever run against our own
// component CSS at test time (trusted, small), so the backtracking sonarjs flags is not a ReDoS risk.
// eslint-disable-next-line sonarjs/super-linear-regex
const PORTRAIT_BLOCK_RE = /@media[^(]*\([^)]*orientation:\s*portrait[^)]*\)\s*\{([\s\S]*?)(?=@media\s|\s*$)/g;

/** Returns true if any @media (orientation: portrait) block in cssText
 *  contains `selector { ... prop ... value ... }`. */
function portraitBlockHas(cssText: string, selector: string, prop: string, value: string): boolean {
  const portraitBlocks = cssText.match(PORTRAIT_BLOCK_RE) ?? [];
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

/** Extracts the value of a CSS property from a selector inside a portrait @media block. */
function getPortraitPropertyValue(cssText: string, selector: string, prop: string): string | null {
  const portraitBlocks = cssText.match(PORTRAIT_BLOCK_RE) ?? [];
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

function globalSelectorHas(cssText: string, selector: string, prop: string, value: string): boolean {
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

  // In portrait mode the :host is itself rotated, so .modal-overlay must switch
  // to position:absolute (relative to the already-covering rotated :host).
  // position:fixed inside a CSS transform anchors to the rotated element,
  // not the real viewport — the modal appears off-screen on mobile.
  it('.modal-overlay overrides to position:absolute inside the portrait media query', () => {
    expect(portraitBlockHas(modalCss, '.modal-overlay', 'position', 'absolute')).toBe(true);
  });

  it(':host is rotated 90deg in portrait mode (matching the board rotation)', () => {
    expect(portraitBlockHas(modalCss, ':host', 'transform', 'rotate(90deg)')).toBe(true);
  });

  it(':host uses position:fixed in portrait mode so it covers the full rotated screen', () => {
    expect(portraitBlockHas(modalCss, ':host', 'position', 'fixed')).toBe(true);
  });

  // pointer-events:none prevents the invisible host from blocking touches
  // when no modal content is being shown.
  it(':host has pointer-events:none in portrait mode', () => {
    expect(portraitBlockHas(modalCss, ':host', 'pointer-events', 'none')).toBe(true);
  });

  // Regression: 100vh includes the Android navigation-bar area; 100dvh is the
  // usable height only. Using vh causes the right edge of the rotated landscape
  // view to be hidden behind the navigation bar.
  it(':host uses 100dvh for width (not plain vh) so the nav bar is excluded', () => {
    expect(portraitBlockHas(modalCss, ':host', 'width', '100dvh')).toBe(true);
  });

  it(':host uses 100dvw for height (not plain vw) so the nav bar is excluded', () => {
    expect(portraitBlockHas(modalCss, ':host', 'height', '100dvw')).toBe(true);
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

  it(':host is rotated 90deg in portrait mode', () => {
    expect(portraitBlockHas(boardCss, ':host', 'transform', 'rotate(90deg)')).toBe(true);
  });

  it(':host has overflow:hidden in portrait mode to clip the rotated content', () => {
    expect(portraitBlockHas(boardCss, ':host', 'overflow', 'hidden')).toBe(true);
  });

  // Regression: 100vh includes the Android navigation-bar area; 100dvh is the
  // usable height only. Using vh causes the right edge of the rotated landscape
  // view to be hidden behind the navigation bar.
  it(':host uses 100dvh for width (not plain vh) so the nav bar is excluded', () => {
    expect(portraitBlockHas(boardCss, ':host', 'width', '100dvh')).toBe(true);
  });

  it(':host uses 100dvw for height (not plain vw) so the nav bar is excluded', () => {
    expect(portraitBlockHas(boardCss, ':host', 'height', '100dvw')).toBe(true);
  });

  // zoom lives outside the portrait media query so it also fires when the device
  // is physically rotated to landscape — applyMobileScale() then scales the board
  // to fit within the landscape viewport height (390 px).
  it('.board-layout has zoom:var(--mobile-scale,1) outside any media query', () => {
    expect(globalSelectorHas(boardCss, '.board-layout', 'zoom', '--mobile-scale')).toBe(true);
  });
});
