import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { map } from 'rxjs';
import { SheetProgress, SheetRow } from '../../generated';
import { MiniSheetComponent } from '../mini-sheet/mini-sheet.component';
import { VariantCatalogService } from '../services/variant-catalog.service';
import { closedLastLockedRow, playedProgress } from './played-progress.util';

interface MiniCatalogEntry {
  key: string;
  rows: SheetRow[];
  played: SheetProgress;
  closedRows: Record<string, string>;
}

/**
 * Debug page: the player-list mini sheet for every sheet variant, each shown pristine and
 * half-played. The mini sheet is otherwise only reachable by starting a real multi-player game,
 * which makes a rendering bug in a rarely-picked variant easy to miss. Not linked from the UI —
 * open /mini-catalog directly.
 */
@Component({
  selector: 'app-mini-sheet-catalog',
  imports: [MiniSheetComponent],
  templateUrl: './mini-sheet-catalog.component.html',
  styleUrl: './mini-sheet-catalog.component.css',
})
export class MiniSheetCatalogComponent implements OnInit {
  private readonly variantCatalog = inject(VariantCatalogService);

  readonly entries = signal<MiniCatalogEntry[]>([]);
  // Mirrors /option-catalog: a screenshotting tool can wait on [data-catalog-ready].
  readonly ready = computed(() => this.entries().length > 0);

  ngOnInit(): void {
    this.variantCatalog
      .layouts()
      .pipe(map((variants) => variants.map((v) => this.toEntry(v.key, v.layout.rows))))
      .subscribe((entries) => this.entries.set(entries));
  }

  private toEntry(key: string, rows: SheetRow[]): MiniCatalogEntry {
    return { key, rows, played: playedProgress(rows), closedRows: closedLastLockedRow(rows) };
  }
}
