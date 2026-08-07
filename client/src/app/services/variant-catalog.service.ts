import { inject, Injectable } from '@angular/core';
import { forkJoin, map, Observable, of, switchMap } from 'rxjs';
import { GamesService } from '../../generated/api/api';
import { GameOption, SheetLayout } from '../../generated/model/models';

/** One entry of a sheet catalog: the game-option key it came from, and the sheet that option yields. */
export interface VariantLayout {
  key: string;
  layout: SheetLayout;
}

interface PreviewRequest {
  key: string;
  options: Record<string, unknown>;
}

/** The two sheet variants the `base` option offers; every other MODE option is a toggle on top of STANDARD. */
const BASE_VARIANTS = ['STANDARD', 'LONGO'] as const;

/**
 * Loads the previewed sheet layout of every sheet-changing game option — the data behind the
 * catalog pages (/option-catalog for the docs images, /mini-catalog for the mini-sheet debug view).
 */
@Injectable({ providedIn: 'root' })
export class VariantCatalogService {
  private readonly gamesService = inject(GamesService);

  /** One layout per sheet variant, in the order the README catalog lists them. */
  layouts(): Observable<VariantLayout[]> {
    return this.gamesService.getGameOptions().pipe(
      map((options) => this.previewRequests(options.filter((o) => o.category === GameOption.CategoryEnum.MODE))),
      switchMap((requests) => this.previewAll(requests)),
    );
  }

  private previewAll(requests: PreviewRequest[]): Observable<VariantLayout[]> {
    // forkJoin over an empty array never emits, which would leave a catalog page waiting forever.
    if (requests.length === 0) return of([]);
    return forkJoin(
      requests.map((request) =>
        this.gamesService.previewLayout(request.options).pipe(map((layout) => ({ key: request.key, layout }))),
      ),
    );
  }

  private previewRequests(modeOptions: GameOption[]): PreviewRequest[] {
    return modeOptions.flatMap((option) =>
      option.key === 'base'
        ? BASE_VARIANTS.map((base) => ({ key: base.toLowerCase(), options: { base } }))
        : [{ key: option.key, options: { base: 'STANDARD', [option.key]: true } }],
    );
  }
}
