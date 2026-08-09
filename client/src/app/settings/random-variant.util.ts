/**
 * Selection logic behind the "random game" button.
 *
 * Deliberately pure and free of the form: it decides *what* to pick, the component decides
 * how to apply it. Injecting `random` is what makes the 75/25 weighting testable.
 */

/** Key of the enum option holding the base sheet. */
export const BASE_VARIANT_KEY = 'base';

export const STANDARD_VARIANT = 'STANDARD';
export const LONGO_VARIANT = 'LONGO';

/** Share of random games that use the STANDARD sheet; LONGO gets the remainder. */
export const STANDARD_VARIANT_ODDS = 0.75;

export interface RandomVariant {
  /** Base sheet — STANDARD or LONGO. */
  base: string;
  /** The single extra game mode to switch on, or null when there are none to choose from. */
  extraMode: string | null;
}

/**
 * Picks a base sheet (STANDARD 75% of the time, LONGO the other 25%) plus exactly one extra
 * game mode drawn uniformly from `modeKeys`.
 *
 * Only sheet variants are chosen here — general settings such as "see other cards" or the
 * bot count are none of this function's business and are left alone by the caller.
 */
export function pickRandomVariant(modeKeys: string[], random: () => number = Math.random): RandomVariant {
  return {
    base: random() < STANDARD_VARIANT_ODDS ? STANDARD_VARIANT : LONGO_VARIANT,
    extraMode: pickOne(modeKeys, random),
  };
}

function pickOne(keys: string[], random: () => number): string | null {
  if (keys.length === 0) return null;
  // Clamp so a random() of exactly 1 can't index past the end — Math.random never returns
  // 1, but an injected generator might.
  const index = Math.min(Math.floor(random() * keys.length), keys.length - 1);
  return keys[index]!;
}
