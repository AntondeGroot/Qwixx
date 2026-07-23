import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { forkJoin, map } from 'rxjs';
// TODO(boundaries): move this data access behind an app service so the component doesn't inject the
// generated API layer directly. Existing debt — the rule forbids any NEW component → generated-api edge.
// eslint-disable-next-line boundaries/dependencies
import { GamesService } from '../../generated/api/api';
import { GameOption, SheetLayout } from '../../generated/model/models';
import { RowComponent } from '../row/row.component';

interface CatalogItem {
  key: string;
  layout: SheetLayout;
  doubleVariant: 'A' | 'B' | null;
}

/**
 * A headless-friendly catalog page: renders the real sheet preview for every sheet-changing
 * (category MODE) game option, each tagged with `data-opt-key`. The docs image generator
 * screenshots each `[data-opt-key]` block, so the images always match the live styling.
 * Not linked from the UI — reached only at /option-catalog by the generator.
 */
@Component({
  selector: 'app-option-catalog',
  imports: [RowComponent],
  templateUrl: './option-catalog.component.html',
  styleUrl: './option-catalog.component.css',
})
export class OptionCatalogComponent implements OnInit {
  private readonly gamesService = inject(GamesService);

  readonly items = signal<CatalogItem[]>([]);
  // The generator waits on [data-catalog-ready] before screenshotting.
  readonly ready = computed(() => this.items().length > 0);

  ngOnInit(): void {
    this.gamesService.getGameOptions().subscribe((opts) => {
      // Only MODE options change the sheet and have a meaningful preview (base + variant toggles).
      const modeOptions = opts.filter((o) => o.category === GameOption.CategoryEnum.MODE);
      forkJoin(
        modeOptions.map((o) =>
          this.gamesService
            .previewLayout(this.previewRequestFor(o.key))
            .pipe(map((layout) => ({ key: o.key, layout, doubleVariant: this.doubleVariantFor(o.key) }))),
        ),
      ).subscribe((items) => this.items.set(items));
    });
  }

  // base → show the LONGO sheet (its non-default value); every other MODE option is a boolean
  // toggled on over the standard sheet.
  private previewRequestFor(key: string): Record<string, unknown> {
    return key === 'base' ? { base: 'LONGO' } : { base: 'STANDARD', [key]: true };
  }

  private doubleVariantFor(key: string): 'A' | 'B' | null {
    if (key === 'doubleA') return 'A';
    if (key === 'doubleB') return 'B';
    return null;
  }
}
