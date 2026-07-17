import { TestBed } from '@angular/core/testing';
import { ScoreAnimationService, Col, PlayerRow } from './score-animation.service';
import type { ScoreCard } from '../../generated/model/models';

describe('ScoreAnimationService', () => {
  let service: ScoreAnimationService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(ScoreAnimationService);
    service.reset();
  });

  const card = (over: Partial<ScoreCard>): ScoreCard =>
    ({ pointsPerColor: {}, extraPoints: 0, bonusPoints: 0, punishmentPoints: 0, ...over }) as unknown as ScoreCard;

  const player = (id: string, scoreCard: ScoreCard): PlayerRow => ({
    id,
    name: id,
    scoreCard,
    displayed: {},
    displayedPunishment: 0,
    rank: 0,
    lifting: false,
  });

  const colourCol = (key: string): Col => ({
    key,
    getValue: (sc) => sc.pointsPerColor[key] ?? 0,
    isDoubled: (sc) => sc.doubledColor === key,
  });

  const displayedOf = (id: string, key: string): number | undefined =>
    service.playerRows().find((r) => r.id === id)?.displayed[key];

  describe('the Bonus B ×2 beat', () => {
    // The animation's own `ms` callback fires synchronously at every beat, so it doubles as a probe:
    // each call records what the screen showed at that moment, building a trace to assert against.
    const traceRun = async (cols: Col[], rows: PlayerRow[], watch: { id: string; key: string }) => {
      const trace: { doubling: string | null; value: number | undefined }[] = [];
      await service.runSequence(cols, rows, () => {
        trace.push({ doubling: service.doublingKey(), value: displayedOf(watch.id, watch.key) });
        return 0; // collapse every delay so the sequence runs instantly
      });
      return trace;
    };

    it('counts the plain value first, then doubles it once the chip is revealed', async () => {
      const alice = player('alice', card({ pointsPerColor: { RED: 12 }, doubledColor: 'RED' }));

      const trace = await traceRun([colourCol('RED')], [alice], { id: 'alice', key: 'RED' });

      // While the chip is up but before the doubling count, the plain 6 is on screen — the doubling
      // is a visible second act rather than being folded into the first count-up.
      expect(trace).toContainEqual({ doubling: 'RED', value: 6 });
      expect(displayedOf('alice', 'RED')).toBe(12);
      expect(service.doubledKeys().has('RED')).toBe(true);
    });

    it('reveals the chip only after its column has counted up', async () => {
      const alice = player('alice', card({ pointsPerColor: { RED: 12 }, doubledColor: 'RED' }));

      const trace = await traceRun([colourCol('RED')], [alice], { id: 'alice', key: 'RED' });

      // Nothing is ever shown as doubled while the plain count-up is still climbing from 0.
      const early = trace.filter((t) => t.doubling !== null && (t.value ?? 0) < 6);
      expect(early).toEqual([]);
    });

    it('leaves columns nobody doubled with their original rhythm', async () => {
      const alice = player('alice', card({ pointsPerColor: { RED: 10 } }));

      const trace = await traceRun([colourCol('RED')], [alice], { id: 'alice', key: 'RED' });

      expect(trace.every((t) => t.doubling === null)).toBe(true);
      expect(service.doubledKeys().size).toBe(0);
      expect(displayedOf('alice', 'RED')).toBe(10);
    });

    it('re-sorts on the plain values before the double, then again after it', async () => {
      // Bob leads on plain values (10 vs 6) but Alice's ×2 takes her to 12 and the lead.
      const alice = player('alice', card({ pointsPerColor: { RED: 12 }, doubledColor: 'RED' }));
      const bob = player('bob', card({ pointsPerColor: { RED: 10 } }));

      const ranks: { doubling: string | null; leader: string | undefined }[] = [];
      await service.runSequence([colourCol('RED')], [alice, bob], () => {
        ranks.push({
          doubling: service.doublingKey(),
          leader: service.playerRows().find((r) => r.rank === 0)?.id,
        });
        return 0;
      });

      // Bob is shown in front while the chip is up and Alice still sits on her plain 6...
      expect(ranks).toContainEqual({ doubling: 'RED', leader: 'bob' });
      // ...and the double flips the standings afterwards.
      expect(service.playerRows().find((r) => r.rank === 0)?.id).toBe('alice');
    });
  });

  describe('showInstant', () => {
    it('shows the ×2 chip, since the values it renders already include the double', () => {
      const alice = player('alice', card({ pointsPerColor: { RED: 12 }, doubledColor: 'RED' }));

      service.showInstant([colourCol('RED')], [alice]);

      expect(service.doubledKeys().has('RED')).toBe(true);
      expect(displayedOf('alice', 'RED')).toBe(12);
    });

    it('shows no chip when nobody doubled', () => {
      const alice = player('alice', card({ pointsPerColor: { RED: 10 } }));

      service.showInstant([colourCol('RED')], [alice]);

      expect(service.doubledKeys().size).toBe(0);
    });
  });
});
