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
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const dir = dirname(fileURLToPath(import.meta.url));
function css(rel: string) { return readFileSync(join(dir, rel), 'utf-8'); }

// ── Helpers ────────────────────────────────────────────────────────────────

/** Returns true if any @media (orientation: portrait) block in cssText
 *  contains `selector { ... prop ... value ... }`. */
function portraitBlockHas(cssText: string, selector: string, prop: string, value: string): boolean {
  const portraitBlocks = cssText.match(
    /@media\s*\([^)]*orientation:\s*portrait[^)]*\)\s*\{([\s\S]*?)(?=@media\s|\s*$)/g
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

  it(':host is rotated 90deg in portrait mode', () => {
    expect(portraitBlockHas(scoreCss, ':host', 'transform', 'rotate(90deg)')).toBe(true);
  });

  // Regression: zoom < 1 on .score-screen shrinks the content to a fraction of
  // the viewport, leaving blank background visible around it on mobile.
  // Only rotation is needed — no zoom scaling.
  it('.score-screen must not have zoom applied in portrait mode', () => {
    expect(portraitBlockHas(scoreCss, '.score-screen', 'zoom', '')).toBe(false);
  });

  it('.modal-overlay uses position:fixed in the default rule', () => {
    expect(globalSelectorHas(scoreCss, '.modal-overlay', 'position', 'fixed')).toBe(true);
  });

  // Same fix: inside the rotated :host the overlay must use position:absolute.
  it('.modal-overlay overrides to position:absolute inside the portrait media query', () => {
    expect(portraitBlockHas(scoreCss, '.modal-overlay', 'position', 'absolute')).toBe(true);
  });

  // Regression: default :host has min-height:100vh which overrides height:100vw in portrait,
  // making :host 844×844 instead of 844×390. The overlay fills this oversized box and its
  // AABB after rotation extends outside the viewport.
  it(':host resets min-height in portrait mode so height:100dvw is not overridden', () => {
    expect(portraitBlockHas(scoreCss, ':host', 'min-height', '0')).toBe(true);
  });

  // Regression: 100vh includes the Android navigation-bar area; 100dvh is the
  // usable height only. Using vh causes the right edge of the rotated landscape
  // view to be hidden behind the navigation bar.
  it(':host uses 100dvh for width (not plain vh) so the nav bar is excluded', () => {
    expect(portraitBlockHas(scoreCss, ':host', 'width', '100dvh')).toBe(true);
  });

  it(':host uses 100dvw for height (not plain vw) so the nav bar is excluded', () => {
    expect(portraitBlockHas(scoreCss, ':host', 'height', '100dvw')).toBe(true);
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
});
