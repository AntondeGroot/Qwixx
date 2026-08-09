import { LONGO_VARIANT, pickRandomVariant, STANDARD_VARIANT, STANDARD_VARIANT_ODDS } from './random-variant.util';

/** Feeds pickRandomVariant a scripted sequence — first draw picks the base, second the mode. */
function scripted(...values: number[]): () => number {
  let next = 0;
  return () => values[Math.min(next++, values.length - 1)]!;
}

describe('pickRandomVariant', () => {
  const MODE_KEYS = ['xChange', 'luckyNumber', 'doubleA', 'bonusA'];

  // The boundary is the whole point of the 75/25 split. Math.random is uniform over [0, 1),
  // so LONGO gets exactly a quarter of the draws only if the cut is inclusive on its side:
  // everything below 0.75 is STANDARD, 0.75 itself already counts as LONGO.
  it('picks LONGO only from the 75% mark upwards', () => {
    expect(pickRandomVariant(MODE_KEYS, scripted(0)).base).toBe(STANDARD_VARIANT);
    expect(pickRandomVariant(MODE_KEYS, scripted(STANDARD_VARIANT_ODDS - Number.EPSILON)).base).toBe(STANDARD_VARIANT);
    expect(pickRandomVariant(MODE_KEYS, scripted(STANDARD_VARIANT_ODDS)).base).toBe(LONGO_VARIANT);
    expect(pickRandomVariant(MODE_KEYS, scripted(0.999999)).base).toBe(LONGO_VARIANT);
  });
});
