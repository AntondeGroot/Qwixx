import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { map } from 'rxjs';
import { SheetLayout } from '../../generated/model/models';
import { connectorTargetIds } from '../connector-overlay/connector-links.util';
import { ConnectorOverlayComponent } from '../connector-overlay/connector-overlay.component';
import { RowComponent } from '../row/row.component';
import { VariantCatalogService } from '../services/variant-catalog.service';

interface CatalogItem {
  key: string;
  layout: SheetLayout;
  doubleVariant: 'A' | 'B' | null;
  bonusNumbers: number[];
  // Connected B target cells that get the dotted ring (a CSS pseudo on the cell), same as the board.
  targetIds: Set<string>;
}

/**
 * A headless-friendly catalog page: renders the real sheet preview for every sheet-changing
 * (category MODE) game option, each tagged with `data-opt-key`. The docs image generator
 * screenshots each `[data-opt-key]` block, so the images always match the live styling.
 * Not linked from the UI — reached only at /option-catalog by the generator.
 */
@Component({
  selector: 'app-option-catalog',
  imports: [RowComponent, ConnectorOverlayComponent],
  templateUrl: './option-catalog.component.html',
  styleUrl: './option-catalog.component.css',
})
export class OptionCatalogComponent implements OnInit {
  private readonly variantCatalog = inject(VariantCatalogService);

  readonly items = signal<CatalogItem[]>([]);
  // The generator waits on [data-catalog-ready] before screenshotting.
  readonly ready = computed(() => this.items().length > 0);

  ngOnInit(): void {
    this.variantCatalog
      .layouts()
      .pipe(map((variants) => variants.map((v) => this.toCatalogItem(v.key, v.layout))))
      .subscribe((items) => this.items.set(items));
  }

  private toCatalogItem(key: string, layout: SheetLayout): CatalogItem {
    return {
      key,
      layout,
      doubleVariant: this.doubleVariantFor(key),
      bonusNumbers: layout.bonusNumbers ?? [],
      targetIds: connectorTargetIds(layout),
    };
  }

  private doubleVariantFor(key: string): 'A' | 'B' | null {
    if (key === 'doubleA') return 'A';
    if (key === 'doubleB') return 'B';
    return null;
  }
}
