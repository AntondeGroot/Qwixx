import {
  afterEveryRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { environment } from '../../environments/environment';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, Subscription } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateService } from '@ngx-translate/core';
// TODO(boundaries): move this data access behind an app service so the component doesn't inject the
// generated API layer directly. Existing debt — the rule forbids any NEW component → generated-api edge.
// eslint-disable-next-line boundaries/dependencies
import { GamesService, PlayersService } from '../../generated/api/api';
import { GameOption, SheetLayout } from '../../generated/model/models';
import { connectorTargetIds } from '../connector-overlay/connector-links.util';
import { ConnectorOverlayComponent } from '../connector-overlay/connector-overlay.component';
import { RowComponent } from '../row/row.component';
import { SilverMarkComponent } from '../silver-mark/silver-mark.component';
import { LobbyService } from '../services/lobby.service';
import { EmbedModeService } from '../services/embed-mode.service';

// Qwixx seats at most 5 players total (humans + bots). Bots are added on top of the human
// seats server-side, so this — not the room's human capacity — bounds how many bots may be added.
const MAX_TOTAL_PLAYERS = 5;

@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule, RowComponent, SilverMarkComponent, ConnectorOverlayComponent],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class SettingsComponent implements OnInit, OnDestroy {
  private readonly gamesService = inject(GamesService);
  private readonly playersService = inject(PlayersService);
  private readonly lobbyService = inject(LobbyService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  protected readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);
  readonly embedMode = inject(EmbedModeService);
  private previewSub?: Subscription;

  // Present when navigating here from the score screen after a game ends.
  // Both are required for restart mode; absent means standalone (offline) mode.
  sessionId = signal<string | null>(null);
  playerId = signal<string | null>(null);

  get isRestartMode(): boolean {
    return !!this.sessionId() && !!this.playerId();
  }

  gameOptions = signal<GameOption[]>([]);
  availableGameOptions = computed(() => this.gameOptions().filter((o) => !o.adminOnly || this.embedMode.isAdmin()));

  // Group options for the settings UI under section headers. GENERAL (match/player setup)
  // renders first, then MODE (sheet-layout variants); an absent category is treated as MODE.
  // Order within a group follows the server's option order. Empty groups are dropped.
  readonly optionSections: { category: GameOption.CategoryEnum; labelKey: string }[] = [
    { category: GameOption.CategoryEnum.GENERAL, labelKey: 'gameOption.section.general' },
    { category: GameOption.CategoryEnum.MODE, labelKey: 'gameOption.section.modes' },
  ];
  groupedOptions = computed(() => {
    const opts = this.availableGameOptions();
    return this.optionSections
      .map((section) => ({
        labelKey: section.labelKey,
        options: opts.filter((o) => (o.category ?? GameOption.CategoryEnum.MODE) === section.category),
      }))
      .filter((group) => group.options.length > 0);
  });
  lobbyPlayers = signal<{ id: string; name: string }[]>([]);
  previewLayout = signal<SheetLayout | null>(null);
  // Connected B target cells that get the dotted ring (a CSS pseudo on the cell), same as the board.
  readonly previewTargetIds = computed(() => {
    const layout = this.previewLayout();
    return layout ? connectorTargetIds(layout) : new Set<string>();
  });
  // Which double variant the preview should render (derived from the doubleA/doubleB options),
  // since the previewLayout payload itself doesn't carry the flags.
  previewDoubleVariant = signal<'A' | 'B' | null>(null);
  error = signal<string | null>(null);
  loading = signal(false);

  botCount = signal<number>(0);
  botSlots = computed(() => Array.from({ length: this.botCount() }, (_, i) => i + 1));
  // Bots fill the seats left over after the human players, capped at the Qwixx table size.
  // lobbyPlayers holds only the humans (the finished game's bots don't count), so a fresh set
  // of bots can always be chosen — e.g. after a 1-human + 2-bot game, up to 4 bots remain.
  maxBotCount = computed(() => Math.max(0, MAX_TOTAL_PLAYERS - this.lobbyPlayers().length));

  /** Selects an enum option value (used by the Variant slider toggle). */
  setOption(key: string, value: string): void {
    this.form.get(key)?.setValue(value);
  }

  /** Index of the currently-selected choice — drives the slider thumb's position. */
  variantIndex(opt: GameOption): number {
    return Math.max(0, (opt.choices ?? []).indexOf(this.form.get(opt.key)?.value));
  }

  // Suppress lobby → form updates while the player is actively editing
  private suppressLobbySync = false;

  private readonly boardPreviewOuter = viewChild<ElementRef>('boardPreviewOuter');
  readonly previewScale = signal(1);
  private previewResizeObserver?: ResizeObserver;

  form!: FormGroup;

  readonly TypeEnum = GameOption.TypeEnum;

  constructor() {
    afterEveryRender(() => {
      this.updatePreviewScale();
      const outer = this.boardPreviewOuter()?.nativeElement;
      if (outer && !this.previewResizeObserver) {
        this.previewResizeObserver = new ResizeObserver(() => this.updatePreviewScale());
        this.previewResizeObserver.observe(outer);
      }
    });
  }

  private updatePreviewScale(): void {
    const outer = this.boardPreviewOuter()?.nativeElement as HTMLElement | undefined;
    if (!outer) return;
    const inner = outer.firstElementChild as HTMLElement | null;
    if (!inner) return;
    const currentZoom = parseFloat(inner.style.zoom) || 1;
    const naturalW = inner.getBoundingClientRect().width / currentZoom;
    const containerW = outer.clientWidth;
    const scale = naturalW > containerW ? containerW / naturalW : 1;
    if (scale !== this.previewScale()) this.previewScale.set(scale);
  }

  /** Re-types an option's string value (form default, or a URL/host override) to its declared type. */
  // The union return is the point: the value's type depends on the option's declared type.
  // eslint-disable-next-line sonarjs/function-return-type
  private coerceOptionFromString(type: GameOption.TypeEnum | undefined, raw: string): boolean | number | string {
    if (type === GameOption.TypeEnum.BOOLEAN) return raw === 'true';
    if (type === GameOption.TypeEnum.INTEGER) return Number(raw);
    return raw;
  }

  ngOnInit() {
    const params = this.route.snapshot.queryParamMap;

    // Query params are authoritative; fall back to sessionStorage written by newGame().
    this.sessionId.set(params.get('sessionId') || sessionStorage.getItem('qwixx_lobby_sid') || null);
    this.playerId.set(params.get('playerId') || sessionStorage.getItem('qwixx_lobby_pid') || null);

    // Embed mode: hosted inside another app's iframe (e.g. GWT GameRoom).
    // Option changes are pushed to the parent via postMessage; the service handles transport.
    const isEmbed = params.get('embed') === '1';
    if (isEmbed) {
      this.embedMode.enable((overrides) => this.applyLobbyOptions(overrides), this.destroyRef);
    }

    // Allow the host app to set the UI language via ?lang=
    const langParam = params.get('lang');
    if (langParam) {
      this.translate.use(langParam);
    }

    // Initial options from the host app, serialised as a JSON string in ?options=.
    // The GWT may also pass individual integer options (e.g. botCount) as top-level
    // query params because it manages those through its own widgets.
    const optionsParam = params.get('options');
    const botCountParam = params.get('botCount');

    this.form = this.fb.group({});

    this.gamesService.getGameOptions().subscribe((opts) => {
      for (const opt of opts) {
        this.form.addControl(opt.key, this.fb.control(this.coerceOptionFromString(opt.type, opt.defaultValue)));
      }
      this.gameOptions.set(opts);
      const wiredPairs = new Set<string>();
      for (const opt of opts) {
        for (const other of opt.incompatibleWith ?? []) {
          const pairKey = [opt.key, other].sort((a, b) => a.localeCompare(b)).join('|');
          if (!wiredPairs.has(pairKey)) {
            wiredPairs.add(pairKey);
            this.setupMutualExclusion(opt.key, other);
          }
        }
      }

      // Apply initial options supplied by the host app before fetching the preview.
      if (optionsParam) {
        try {
          const init = JSON.parse(optionsParam) as Record<string, string>;
          for (const [k, v] of Object.entries(init)) {
            const ctrl = this.form.get(k);
            if (!ctrl) continue;
            const opt = opts.find((o) => o.key === k);
            ctrl.setValue(this.coerceOptionFromString(opt?.type, v), { emitEvent: false });
          }
        } catch {
          /* ignore malformed options */
        }
      }

      // Standalone botCount param overrides anything in the JSON (GWT may send it separately).
      if (botCountParam !== null) {
        this.form.get('botCount')?.setValue(Number(botCountParam), { emitEvent: false });
      }

      this.fetchPreview();

      // Keep botCount signal in sync with the form for all modes
      this.form.valueChanges
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((v) => this.botCount.set(Number(v['botCount'] ?? 0)));
      this.botCount.set(Number(this.form.get('botCount')?.value ?? 0));

      if (isEmbed) {
        // Immediately post the current options so the host has them even before
        // the user changes anything.
        this.postOptionsToParent();
        // Push every subsequent change to the parent frame and refresh the preview.
        this.form.valueChanges.pipe(debounceTime(100), takeUntilDestroyed(this.destroyRef)).subscribe(() => {
          this.postOptionsToParent();
          this.fetchPreview();
        });
      } else if (this.isRestartMode) {
        this.startLobbySync();
        this.startGameStartSse();

        // Push local form changes to the lobby so other players see them.
        this.form.valueChanges
          .pipe(
            debounceTime(400),
            distinctUntilChanged((a, b) => JSON.stringify(a) === JSON.stringify(b)),
            takeUntilDestroyed(this.destroyRef),
          )
          .subscribe(() => {
            if (!this.suppressLobbySync) {
              this.lobbyService
                .update(this.sessionId()!, this.buildGameOptions())
                // eslint-disable-next-line @typescript-eslint/no-empty-function
                .subscribe({ error: () => {} });
            }
            this.fetchPreview();
          });
      } else {
        this.form.valueChanges.subscribe(() => this.fetchPreview());
      }
    });
  }

  private postOptionsToParent(): void {
    const raw = this.buildGameOptions();
    const stringOpts = Object.fromEntries(Object.entries(raw).map(([k, v]) => [k, String(v)]));
    this.embedMode.postOptions(stringOpts);
  }

  ngOnDestroy() {
    this.previewResizeObserver?.disconnect();
    this.previewSub?.unsubscribe();
  }

  private startLobbySync(): void {
    const es = new EventSource(`${environment.apiBaseUrl}/games/${this.sessionId()!}/lobby/stream`);
    this.destroyRef.onDestroy(() => es.close());
    es.onmessage = (event: MessageEvent) => {
      const lobby = JSON.parse(event.data);
      this.lobbyPlayers.set(lobby.players);
      this.applyLobbyOptions(lobby.proposedOptions);
    };
  }

  // Subscribe to SSE; when another player starts the game the server pushes
  // the initial state (gameOver: false) and we navigate to the board.
  private startGameStartSse(): void {
    const es = new EventSource(`${environment.apiBaseUrl}/gamestates/${this.sessionId()!}/stream`);
    this.destroyRef.onDestroy(() => es.close());
    es.onmessage = (event: MessageEvent) => {
      const state = JSON.parse(event.data);
      if (!state.gameOver) {
        es.close();
        void this.router.navigate(['/game', this.sessionId(), this.playerId()]);
      }
    };
  }

  // Update the form with lobby options without triggering an outgoing PUT.
  private applyLobbyOptions(opts: Record<string, unknown>): void {
    if (!opts || Object.keys(opts).length === 0) return;
    this.suppressLobbySync = true;
    for (const [key, value] of Object.entries(opts)) {
      const ctrl = this.form.get(key);
      if (!ctrl) continue;
      const opt = this.gameOptions().find((o) => o.key === key);
      let coerced: unknown = value;
      if (opt?.type === GameOption.TypeEnum.BOOLEAN) coerced = Boolean(value);
      else if (opt?.type === GameOption.TypeEnum.INTEGER) coerced = Number(value);
      if (ctrl.value !== coerced) ctrl.setValue(coerced, { emitEvent: false });
    }
    this.suppressLobbySync = false;
    // setValue above used emitEvent:false so valueChanges didn't fire — re-enforce manually.
    this.enforceMutualExclusions();
    this.fetchPreview();
  }

  private readonly mutualExclusionPairs: [string, string][] = [];

  // Registers a mutually exclusive pair and immediately enforces the current state.
  // Also re-enforces whenever either control changes.
  private setupMutualExclusion(keyA: string, keyB: string): void {
    const ctrlA = this.form.get(keyA);
    const ctrlB = this.form.get(keyB);
    if (!ctrlA || !ctrlB) return;

    this.mutualExclusionPairs.push([keyA, keyB]);
    this.enforceMutualExclusions();

    ctrlA.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.enforceMutualExclusions());
    ctrlB.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.enforceMutualExclusions());
  }

  // Applies all registered mutual exclusions based on current control values.
  // Must be called after any setValue({ emitEvent: false }) path (e.g. lobby sync).
  private enforceMutualExclusions(): void {
    // A control is blocked when ANY incompatible partner is on. Collect every blocked key from the
    // current values FIRST, then apply — otherwise one pair's "enable" could undo another pair's
    // "disable" (e.g. picking doubleA disables doubleB, but the doubleB/bigPoints pair re-enabled it).
    const blocked = new Set<string>();
    for (const [keyA, keyB] of this.mutualExclusionPairs) {
      if (this.form.get(keyA)?.value) blocked.add(keyB);
      if (this.form.get(keyB)?.value) blocked.add(keyA);
    }
    for (const key of new Set(this.mutualExclusionPairs.flat())) {
      const ctrl = this.form.get(key);
      if (!ctrl) continue;
      if (blocked.has(key)) {
        if (ctrl.value) ctrl.setValue(false, { emitEvent: false });
        ctrl.disable({ emitEvent: false });
      } else {
        ctrl.enable({ emitEvent: false });
      }
    }
  }

  private buildGameOptions(): Record<string, unknown> {
    const values = this.form.getRawValue();
    const opts: Record<string, unknown> = {};
    for (const opt of this.gameOptions()) {
      opts[opt.key] = values[opt.key];
    }
    return opts;
  }

  private fetchPreview() {
    this.previewSub?.unsubscribe();
    const opts = this.buildGameOptions();
    let variant: 'A' | 'B' | null = null;
    if (opts['doubleA']) variant = 'A';
    else if (opts['doubleB']) variant = 'B';
    this.previewDoubleVariant.set(variant);
    this.previewSub = this.gamesService.previewLayout(opts).subscribe({
      next: (layout) => this.previewLayout.set(layout),
      error: () => this.previewLayout.set(null),
    });
  }

  startGame() {
    if (this.form.invalid) return;
    this.error.set(null);
    this.loading.set(true);

    const gameOptions = this.buildGameOptions();

    if (this.isRestartMode) {
      this.lobbyService.update(this.sessionId()!, gameOptions).subscribe(() => {
        this.gamesService.restartGame(this.sessionId()!, { gameOptions }).subscribe({
          next: () => {
            this.loading.set(false);
            void this.router.navigate(['/game', this.sessionId(), this.playerId()]);
          },
          error: (e) => this.handleError(e),
        });
      });
    } else {
      this.gamesService.createNewGame({ roomName: 'Offline', maxPlayers: 1, gameOptions }).subscribe({
        next: (res) => {
          const sessionId = res.sessionId!;
          this.playersService.addPlayerToGame(sessionId, { id: this.playerId()!, name: 'Offline' }).subscribe({
            next: (joined) => {
              this.gamesService.startGame(sessionId).subscribe({
                next: () => {
                  this.loading.set(false);
                  void this.router.navigate(['/game', sessionId, joined.playerId]);
                },
                error: (e) => this.handleError(e),
              });
            },
            error: (e) => this.handleError(e),
          });
        },
        error: (e) => this.handleError(e),
      });
    }
  }

  private handleError(err: unknown) {
    this.loading.set(false);
    this.error.set('Could not start game. Is the server running?');
    console.error(err);
  }

  t(key: string): string {
    return this.translate.instant(key);
  }

  effectiveMax(opt: GameOption): number | null {
    if (opt.key === 'botCount' && this.isRestartMode) {
      // Never exceed the option's own max, but shrink it further if few seats remain.
      return opt.maxValue != null ? Math.min(opt.maxValue, this.maxBotCount()) : this.maxBotCount();
    }
    return opt.maxValue ?? null;
  }

  /** True when the integer range is small enough to show as a select (≤ 10 steps). */
  isSmallIntegerRange(opt: GameOption): boolean {
    const min = opt.minValue ?? 0;
    const max = this.effectiveMax(opt) ?? opt.maxValue ?? null;
    return max !== null && max - min <= 10;
  }

  /** [min, min+1, …, max] used for select options in small integer ranges. */
  integerRange(opt: GameOption): number[] {
    const min = opt.minValue ?? 0;
    const max = this.effectiveMax(opt) ?? opt.maxValue ?? min;
    return Array.from({ length: max - min + 1 }, (_, i) => min + i);
  }
}
