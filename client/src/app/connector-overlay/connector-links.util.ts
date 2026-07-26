import { CellTag, SheetCell, SheetLayout } from '../../generated/model/models';

// One auto-cross connector between two cells, derived purely from the layout's AutoCross tags.
// Two-way (mutual) links are Connected A "column" connectors; one-way links are Connected B arrows
// that point from the source to the target. Pixel geometry is measured later from the real DOM
// (see ConnectorOverlayComponent) — this stays a pure, testable function of the layout alone.
export interface ConnectorLink {
  // Two-way: either end (the pair is deduplicated). One-way: the source cell.
  aId: string;
  // Two-way: the other end. One-way: the target cell the arrow points at.
  bId: string;
  oneWay: boolean;
}

// All connector links in a sheet. A link is two-way when its target links back to the source
// (Connected A, drawn as a plain column line, deduplicated per unordered pair); otherwise it is a
// one-way Connected B arrow. Links whose target cell is missing from the layout are skipped.
export function computeConnectorLinks(layout: SheetLayout): ConnectorLink[] {
  const index = new Map<string, SheetCell>();
  for (const row of layout.rows) for (const cell of row.cells) index.set(cell.id, cell);

  const links: ConnectorLink[] = [];
  const seenTwoWay = new Set<string>();
  for (const row of layout.rows) {
    for (const cell of row.cells) {
      for (const target of autoCrossTargets(cell, index)) {
        addLink(links, seenTwoWay, cell, target);
      }
    }
  }
  return links;
}

// The existing cells this cell auto-crosses (its AUTO_CROSS tag targets present in the layout).
function autoCrossTargets(cell: SheetCell, index: Map<string, SheetCell>): SheetCell[] {
  const targets: SheetCell[] = [];
  for (const tag of cell.tags ?? []) {
    if (tag.type !== CellTag.TypeEnum.AUTO_CROSS || !tag.target) continue;
    const target = index.get(tag.target);
    if (target) targets.push(target);
  }
  return targets;
}

function linksBackTo(cell: SheetCell, target: SheetCell): boolean {
  return (target.tags ?? []).some((t) => t.type === CellTag.TypeEnum.AUTO_CROSS && t.target === cell.id);
}

function addLink(links: ConnectorLink[], seenTwoWay: Set<string>, cell: SheetCell, target: SheetCell): void {
  if (!linksBackTo(cell, target)) {
    links.push({ aId: cell.id, bId: target.id, oneWay: true });
    return;
  }
  const key = [cell.id, target.id].sort((a, b) => a.localeCompare(b)).join('|'); // dedupe the mutual pair
  if (seenTwoWay.has(key)) return;
  seenTwoWay.add(key);
  links.push({ aId: cell.id, bId: target.id, oneWay: false });
}

// Ids of the cells a one-way (Connected B) arrow points at — these get the dotted target ring
// (rendered as a CSS pseudo-element on the cell itself, so it always sits exactly on the cell).
export function connectorTargetIds(layout: SheetLayout): Set<string> {
  return new Set(
    computeConnectorLinks(layout)
      .filter((l) => l.oneWay)
      .map((l) => l.bId),
  );
}
