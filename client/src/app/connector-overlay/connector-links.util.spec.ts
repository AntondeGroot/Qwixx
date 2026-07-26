import { CellTag, SheetLayout } from '../../generated/model/models';
import { computeConnectorLinks, connectorTargetIds } from './connector-links.util';

const auto = (target: string) => ({ type: CellTag.TypeEnum.AUTO_CROSS, target });

// r0.src → r1.tgt is one-way (tgt has no back-reference); r0.twoA ↔ r1.twoB is a mutual pair.
const layout = {
  rows: [
    {
      id: 'r0',
      cells: [
        { id: 'src', position: 3, color: 'RED', tags: [auto('tgt')] },
        { id: 'twoA', position: 7, color: 'RED', tags: [auto('twoB')] },
      ],
    },
    {
      id: 'r1',
      cells: [
        { id: 'tgt', position: 2, color: 'YELLOW', tags: [] },
        { id: 'twoB', position: 7, color: 'YELLOW', tags: [auto('twoA')] },
      ],
    },
  ],
} as unknown as SheetLayout;

describe('connector-links', () => {
  it('classifies a mutual link as two-way and a link with no back-reference as one-way', () => {
    expect(computeConnectorLinks(layout)).toEqual([
      { aId: 'src', bId: 'tgt', oneWay: true },
      { aId: 'twoA', bId: 'twoB', oneWay: false },
    ]);
  });

  it('deduplicates the mutual pair into a single two-way link', () => {
    expect(computeConnectorLinks(layout).filter((l) => !l.oneWay)).toHaveLength(1);
  });

  it('connectorTargetIds returns only the one-way arrow targets (the dotted-ring cells)', () => {
    expect(connectorTargetIds(layout)).toEqual(new Set(['tgt']));
  });

  it('skips a link whose target cell is missing from the layout', () => {
    const dangling = {
      rows: [{ id: 'r', cells: [{ id: 'a', position: 0, color: 'RED', tags: [auto('ghost')] }] }],
    } as unknown as SheetLayout;
    expect(computeConnectorLinks(dangling)).toEqual([]);
  });
});
