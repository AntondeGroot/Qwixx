import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { SheetLayout } from '../../generated/model/models';
import { computeConnectorLinks } from './connector-links.util';

// A connector line resolved to sheet-local pixel coordinates, ready to draw.
interface MeasuredLink {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  oneWay: boolean;
}

/**
 * One SVG overlay that draws every auto-cross connector for a sheet: Connected A "column" lines
 * (two-way) and Connected B arrows (one-way). It lives inside the `.sheet` element as a sibling of
 * the rows, and measures each line from the real cell rectangles, so the same component works
 * anywhere a sheet of `<app-row>`s is rendered (the live board and the docs option-catalog preview).
 *
 * <p>Coordinates come from layout offsets (offsetLeft/offsetTop) relative to the sheet, not
 * getBoundingClientRect — they are the SVG's own user units and stay invariant to the board's CSS
 * zoom and portrait rotation. Once measured, the host carries {@code data-measured="true"} so a
 * screenshot generator can wait for the connectors to settle.
 */
@Component({
  selector: 'app-connector-overlay',
  templateUrl: './connector-overlay.component.html',
  styleUrl: './connector-overlay.component.css',
})
export class ConnectorOverlayComponent implements AfterViewInit, OnDestroy {
  readonly layout = input.required<SheetLayout>();

  private readonly host = inject(ElementRef<HTMLElement>);

  // The logical links, purely derived from the layout; measured() adds the pixel geometry.
  readonly links = computed(() => computeConnectorLinks(this.layout()));
  readonly measured = signal<MeasuredLink[]>([]);

  private resizeObserver?: ResizeObserver;
  private rafHandle = 0;

  constructor() {
    // Re-measure once the DOM reflects a new set of links (e.g. the layout loaded or changed). This
    // also covers the first render; the ResizeObserver additionally catches later reflows/resizes.
    effect(() => {
      this.links();
      this.scheduleMeasure();
    });
  }

  ngAfterViewInit(): void {
    const sheet = this.sheet();
    if (sheet && typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(() => this.measure());
      this.resizeObserver.observe(sheet); // delivers an initial callback the moment it observes
    }
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    if (this.rafHandle) cancelAnimationFrame(this.rafHandle);
  }

  // Coalesce rapid link changes into a single measurement on the next frame (and never leave a
  // callback pending past teardown, which would measure/setAttribute on a destroyed host).
  private scheduleMeasure(): void {
    if (typeof requestAnimationFrame !== 'function') return;
    if (this.rafHandle) cancelAnimationFrame(this.rafHandle);
    this.rafHandle = requestAnimationFrame(() => {
      this.rafHandle = 0;
      this.measure();
    });
  }

  // The overlay sits inside the sheet; the sheet is the offset container the rows share.
  private sheet(): HTMLElement | null {
    return this.host.nativeElement.parentElement;
  }

  // Resolve each logical link to a line between the two cells' facing edges: from the upper cell's
  // bottom edge to the lower cell's top edge, nudged 4px into the gap so it reads a touch longer than
  // edge-to-edge. A gap-only line spanning a bonus row is handled automatically by the real offsets.
  private measure(): void {
    const sheet = this.sheet();
    const out: MeasuredLink[] = [];
    if (sheet) {
      const cells = cellIndex(sheet); // one subtree scan, not two lookups per link
      for (const link of this.links()) {
        const a = cells.get(link.aId);
        const b = cells.get(link.bId);
        if (!a || !b) continue;
        const pa = offsetInSheet(a, sheet);
        const pb = offsetInSheet(b, sheet);
        const aIsUpper = pa.top <= pb.top;
        out.push({
          x1: pa.left + a.offsetWidth / 2,
          y1: aIsUpper ? pa.top + a.offsetHeight + 4 : pa.top - 4,
          x2: pb.left + b.offsetWidth / 2,
          y2: aIsUpper ? pb.top - 4 : pb.top + b.offsetHeight + 4,
          oneWay: link.oneWay,
        });
      }
    }
    this.measured.set(out);
    this.host.nativeElement.setAttribute('data-measured', 'true');
  }
}

// Every cell in the sheet indexed by its data-cell-id, from a single subtree scan.
function cellIndex(sheet: HTMLElement): Map<string, HTMLElement> {
  const index = new Map<string, HTMLElement>();
  for (const el of sheet.querySelectorAll<HTMLElement>('[data-cell-id]')) {
    const id = el.getAttribute('data-cell-id');
    if (id) index.set(id, el);
  }
  return index;
}

// Top-left of a cell relative to the sheet, summing offsetLeft/offsetTop up the offsetParent chain
// (.cell → .row → .sheet, all position:relative). Layout coords — unaffected by CSS zoom/rotation.
function offsetInSheet(cell: HTMLElement, sheet: HTMLElement): { left: number; top: number } {
  let left = 0;
  let top = 0;
  let node: HTMLElement | null = cell;
  while (node && node !== sheet) {
    left += node.offsetLeft;
    top += node.offsetTop;
    node = node.offsetParent as HTMLElement | null;
  }
  return { left, top };
}
