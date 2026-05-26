import { AfterViewInit, Component, computed, effect, ElementRef, HostListener, inject, OnDestroy, OnInit, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { environment } from '../../environments/environment';
import { TranslateModule } from '@ngx-translate/core';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { MovesService } from '../../generated/api/moves.service';
import { CellTag } from '../../generated/model/cellTag';
import { Color } from '../../generated/model/color';
import { GameState } from '../../generated/model/gameState';
import { MoveRequest } from '../../generated/model/moveRequest';
import { MoveType } from '../../generated/model/moveType';
import { RowState } from '../../generated/model/rowState';
import { SheetCell } from '../../generated/model/sheetCell';
import { SheetLayout } from '../../generated/model/sheetLayout';
import { SheetProgress } from '../../generated/model/sheetProgress';
import { SheetRow } from '../../generated/model/sheetRow';
import { TurnPhase } from '../../generated/model/turnPhase';
import { DiceComponent } from '../dice/dice.component';
import { PlayerListComponent } from '../player-list/player-list.component';
import { RowComponent } from '../row/row.component';
import { ClosureNotification } from '../row-closure-modal/row-closure-modal.component';
import { RowClosureModalService } from '../services/row-closure-modal.service';
import { AudioService } from '../services/audio.service';

@Component({
  selector: 'app-board',
  imports: [RouterLink, RowComponent, DiceComponent, PlayerListComponent, TranslateModule],
  templateUrl: './board.component.html',
  styleUrl: './board.component.css'
})
export class BoardComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly route              = inject(ActivatedRoute);
  private readonly router             = inject(Router);
  private readonly gameStatesService  = inject(GamestatesService);
  private readonly movesService       = inject(MovesService);
  private readonly host               = inject(ElementRef<HTMLElement>);
  private readonly rowClosureModal    = inject(RowClosureModalService);
  private readonly audio              = inject(AudioService);

  sessionId   = signal('');
  playerId    = signal('');
  gameState   = signal<GameState | null>(null);
  error       = signal<string | null>(null);
  rollingDice = signal(false);
  // True while the player dismissed the lock-intent modal to pick a new cell.
  // Suppresses the modal until they cross something (pendingCellIds becomes non-empty).
  private readonly suppressModal = signal(false);
  // True when this player was re-queued mid-turn (not via the normal roll-start).
  // Used to show the two-button modal so they know they can undo (Change) or just pass (OK+board).
  private readonly reQueuedThisTurn = signal(false);

  private eventSource?: EventSource;
  private moveSub?: Subscription;
  private stateSub?: Subscription;

  // Sync modal state to the service so the modal renders at the root level,
  // outside the board's CSS transform (which would break position:fixed on mobile).
  private readonly _modalSync = effect(() => {
    const myName = this.playerName(this.playerId());
    const allRequests = this.gameState()?.closureNotifications ?? [];
    // Show requests from OTHER players only — the declarant never sees their own notification.
    // All players (active and passive) can receive notifications, not just passives.
    const requests = allRequests.filter(r => r.playerName !== myName);

    if (requests.length === 0) {
      // No pending closure from others — reset suppression so the next intent shows fresh.
      untracked(() => this.suppressModal.set(false));
      this.rowClosureModal.clear();
      return;
    }

    const isPassive = this.isInPassiveQueue();
    // Keep modal suppressed when the player is awaiting a lock-declaration auto-send
    // after confirming the YES/NO prompt (Longo second-to-last cell).
    const lockConfirmInProgress = isPassive && this.pendingAutoLock() !== null;
    // Active player re-queued for final look: treat as hard-suppress (their pending crosses
    // are from the active turn, not a passive cross, so auto-unsuppress must not fire).
    const isActiveFinalReview = this.isMyTurn() && isPassive;
    // Passive: auto-unsuppress when they make a new cross (so the modal re-appears).
    // Active (normal or final-review): stay suppressed — they've acknowledged the notification.
    const suppress = lockConfirmInProgress
      || (this.suppressModal() && (!isPassive || isActiveFinalReview || this.pendingCellIds().size === 0));
    if (suppress) {
      this.rowClosureModal.clear();
    } else {
      const wasHidden = untracked(() => this.rowClosureModal.requests().length === 0);
      this.rowClosureModal.show(
        requests,
        () => this.onConfirmRowClosure(),
        () => this.onChangeRowClosure(),
        // Show the two-button layout when there is something actionable.
        // The reQueuedThisTurn flag covers passives who were re-queued mid-turn (e.g. after
        // already having passed): they see [Change = RESET_TURN][OK = dismiss].
        // Fresh passives who haven't acted yet see only [OK = dismiss].
        this.hasPendingPassiveCross() || this.hasPendingActiveCross() || this.hasRevertableEndTurn() || this.hasRevertablePassiveEndTurn() || (this.canPassPassive() && this.reQueuedThisTurn()),
        this.hasPendingPassiveCross()  // confirmEndsRound: show "Confirm last selection" label
      );
      if (wasHidden) this.audio.play(AudioService.ROW_CLOSURE_BELL);
    }
  });
  private rollStartTime = 0;
  private readonly pendingAutoLock = signal<{ rowId: string; autoLock: boolean; cellId: string } | null>(null);

  // Fallback design height used before the game state has rendered.
  private readonly MOBILE_DESIGN_H   = 541;
  private readonly ROLL_ANIM_MIN_MS  = 2800;

  readonly emptySet  = new Set<string>();
  readonly TurnPhase = TurnPhase;

  private gameOverNavigated = false;
  private _gameOverEffect = effect(() => {
    if (this.gameState()?.gameOver && !this.gameOverNavigated) {
      this.gameOverNavigated = true;
      setTimeout(() => {
        this.router.navigate(['/score', this.sessionId()], { queryParams: { pid: this.playerId() } });
      }, 1500);
    }
  });

  isOffline = computed(() => this.gameState() !== null && this.gameState()!.turnState == null);

  visibleClickableCellIds = computed((): Set<string> =>
    this.rollingDice() ? this.emptySet : this.clickableCellIds()
  );

  // Cells reachable via the Longo bonus-number path only: white+white equals a bonus
  // number so the leftmost uncrossed cell in each tied-fewest row gets offered.
  // Tracked separately so onCellClicked can always send CROSS_WHITE_WHITE for them,
  // even when their display value also matches a colour-die combination.
  bonusCellIds = computed((): Set<string> => {
    const state = this.gameState();
    const pid   = this.playerId();
    const turn  = this.turnState();
    if (!state || !turn?.currentRoll || turn.whiteWhiteUsed) return this.emptySet;
    if (!this.isMyTurn() || turn.phase !== TurnPhase.ACTIVE_MOVE) return this.emptySet;

    const roll      = turn.currentRoll;
    const whiteSum  = roll.white1 + roll.white2;
    const bonusNums: number[] = state.bonusNumbers?.[pid] ?? [];
    if (!bonusNums.includes(whiteSum)) return this.emptySet;

    const layout    = state.sheetLayouts[pid];
    const progress  = state.sheetProgress[pid];
    if (!layout) return this.emptySet;
    const closedRows = state.closedRows ?? {};
    const result = new Set<string>();

    let fewest = Infinity;
    for (const row of layout.rows) {
      if (closedRows[row.id]) continue;
      const count = progress?.rowStates[row.id]?.crossedCells?.length ?? 0;
      if (count < fewest) fewest = count;
    }
    if (!isFinite(fewest)) return result;

    for (const row of layout.rows) {
      if (closedRows[row.id]) continue;
      const crossed = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
      if (crossed.size !== fewest) continue;
      const lastPos = crossed.size > 0
        ? Math.max(...row.cells.filter(c => crossed.has(c.id)).map(c => c.position))
        : -1;
      const leftmost = row.cells
        .filter(c => !crossed.has(c.id) && c.position > lastPos)
        .sort((a, b) => a.position - b.position)[0];
      if (leftmost) result.add(leftmost.id);
    }
    return result;
  });

  pendingCellIds = computed(() => {
    const ids = this.gameState()?.turnState?.pendingCrosses?.[this.playerId()] ?? [];
    return new Set<string>(ids);
  });


  ngOnInit() {
    const sid = this.route.snapshot.paramMap.get('sessionId') ?? '';
    const pid = this.route.snapshot.paramMap.get('playerId') ?? '';
    this.sessionId.set(sid);
    this.playerId.set(pid);
    this.fetchState();
    this.setupSse(sid);
  }

  // Re-measure after every game-state render so Longo's bonus chips (80px each)
  // are accounted for — the static MOBILE_DESIGN_H only fits the standard sheet.
  private _scaleEffect = effect(() => {
    this.gameState(); // depend so we re-run when state arrives
    untracked(() => setTimeout(() => this.applyMobileScale(), 0));
  });

  ngAfterViewInit() {
    this.applyMobileScale();
  }

  @HostListener('window:resize')
  applyMobileScale() {
    const el = this.host.nativeElement as HTMLElement;
    if (window.innerHeight <= window.innerWidth) {
      el.style.removeProperty('--mobile-scale');
      return;
    }
    const layout = el.querySelector('.board-layout') as HTMLElement | null;
    if (!layout) {
      // Game state not yet rendered — use the fallback constant.
      el.style.setProperty('--mobile-scale',
        Math.min((window.innerWidth - 16) / this.MOBILE_DESIGN_H, 1).toFixed(4));
      return;
    }
    // Set zoom=1 so offsetHeight gives the natural, unscaled board height.
    // Accessing offsetHeight after the style write forces a synchronous reflow,
    // so the browser computes the layout at zoom=1 before we read the value.
    // We then immediately apply the correct scale in the same JS task — the
    // browser renders only once, so there is no visible flash.
    el.style.setProperty('--mobile-scale', '1');
    const h = layout.offsetHeight;
    const w = layout.offsetWidth;
    // In portrait the board is rotated 90°: DOM height → visual width, DOM width → visual height.
    // Subtract 16px (host padding) from each available dimension.
    // scaleH: fit the board's DOM height into the viewport's width (short side).
    // scaleW: fit the board's DOM width into the viewport's height (long side) —
    //         needed when wide variants (e.g. Longo) make the board wider than 100dvh.
    const scaleH = h > 0 ? (window.innerWidth  - 16) / h : (window.innerWidth  - 16) / this.MOBILE_DESIGN_H;
    const scaleW = w > 0 ? (window.innerHeight - 16) / w : 1;
    el.style.setProperty('--mobile-scale', Math.min(scaleH, scaleW, 1).toFixed(4));
  }

  ngOnDestroy() {
    this.eventSource?.close();
    this.moveSub?.unsubscribe();
    this.stateSub?.unsubscribe();
    this.rowClosureModal.clear();
  }

  private setupSse(sessionId: string): void {
    this.eventSource?.close();
    const es = new EventSource(`${environment.apiBaseUrl}/gamestates/${sessionId}/stream`);
    this.eventSource = es;

    es.onmessage = (event: MessageEvent) => {
      const s: GameState = JSON.parse(event.data);
      if (s.version !== this.gameState()?.version) {
        const prevRoll = this.gameState()?.turnState?.currentRoll;
        const newRoll  = s.turnState?.currentRoll;
        if (this.gameState() !== null && !prevRoll && newRoll) {
          this.rollingDice.set(true);
          this.rollStartTime = Date.now();
        }
        this.applyState(s);
      }
    };

    es.onerror = () => {
      if (es.readyState === EventSource.CLOSED) {
        setTimeout(() => this.setupSse(sessionId), 3000);
      }
    };
  }

  // ── Computed turn helpers ──────────────────────────────────────────────────

  turnState = computed(() => this.gameState()?.turnState ?? null);

  isMyTurn = computed(() =>
    this.turnState()?.activePlayerId === this.playerId()
  );

  isInPassiveQueue = computed(() =>
    (this.turnState()?.passivePlayerQueue ?? []).includes(this.playerId())
  );

  canRoll = computed(() =>
    this.isMyTurn() && this.turnState()?.phase === TurnPhase.ROLL
  );

  canPassActive = computed(() => {
    const turn = this.turnState();
    if (!this.isMyTurn() || turn?.phase !== TurnPhase.ACTIVE_MOVE) return false;
    if (turn.whiteWhiteUsed === true || turn.colorDieUsed === true) return true;
    // Allow EndTurn when this player has a pending lock-closure intent (declared without dice).
    const pid = this.playerId();
    const pendingClosures: Record<string, string> = this.gameState()?.pendingClosures ?? {};
    return Object.values(pendingClosures).some(v => v === pid);
  });

  canGiveUp = computed(() => {
    const turn = this.turnState();
    return this.isMyTurn()
      && turn?.phase === TurnPhase.ACTIVE_MOVE
      && !turn.whiteWhiteUsed
      && !turn.colorDieUsed;
  });

  hasPendingPassiveCross = computed(() =>
    this.isInPassiveQueue() && !this.isMyTurn() && this.pendingCellIds().size > 0
  );

  // Active player has a pending cross (in undo buffer) while a passive has declared.
  // Surfaces the Change/OK buttons in the notification modal for the active player too.
  hasPendingActiveCross = computed(() =>
    this.isMyTurn()
    && this.turnState()?.phase === TurnPhase.ACTIVE_MOVE
    && this.pendingCellIds().size > 0
  );

  // Active player has already EndTurned (phase=PASSIVE_MOVE) but passives are still acting.
  // Allows them to revert their EndTurn via RESET_TURN and make additional moves.
  hasRevertableEndTurn = computed(() =>
    this.isMyTurn()
    && this.turnState()?.phase === TurnPhase.PASSIVE_MOVE
    && (this.turnState()?.passivePlayerQueue?.length ?? 0) > 0
  );

  // Passive player has already EndTurned (left the queue) but other passives are still acting.
  // Allows them to revert their EndTurn via RESET_TURN to reconsider their cross.
  hasRevertablePassiveEndTurn = computed(() => {
    const turn = this.turnState();
    const phase = turn?.phase;
    return !this.isMyTurn()
      && !this.isInPassiveQueue()
      && (phase === TurnPhase.ACTIVE_MOVE || phase === TurnPhase.PASSIVE_MOVE)
      && (turn?.passivePlayerQueue?.length ?? 0) > 0;
  });

  // True when this player has declared a lock intent that is currently pending.
  // Derived from closureNotifications so it works even when no cell was crossed this turn
  // (e.g. the player clicked the lock button directly).
  isDeclarantInLockPending = computed(() => {
    const myName = this.playerName(this.playerId());
    return (this.gameState()?.closureNotifications ?? []).some(r => r.playerName === myName);
  });

  // Row IDs where this player is the pending declarant — used to show the lock ✕
  // even when the undo buffer is empty (no cell crossed this turn).
  declarantPendingLockRowIds = computed((): Set<string> => {
    const state = this.gameState();
    if (!state) return new Set();
    const myName = this.playerName(this.playerId());
    const layout = state.sheetLayouts[this.playerId()];
    if (!layout) return new Set();
    const result = new Set<string>();
    for (const req of state.closureNotifications ?? []) {
      if (req.playerName !== myName) continue;
      const row = layout.rows.find(r => r.lock?.color === req.rowColor);
      if (row) result.add(row.id);
    }
    return result;
  });

  // Row ID of the row the player has committed to closing (pendingAutoLock with autoLock=true).
  // Used to show the lock ✕ immediately after confirming YES on the second-to-last Longo cell,
  // before the server declares the lock intent (which only fires when all required cells are crossed).
  pendingAutoLockRowId = computed(() => {
    const pending = this.pendingAutoLock();
    return pending?.autoLock ? pending.rowId : null;
  });

  canPassPassive = computed(() => {
    const phase = this.turnState()?.phase;
    return this.isInPassiveQueue()
      && (phase === TurnPhase.PASSIVE_MOVE
          || phase === TurnPhase.ACTIVE_MOVE);
  });

  gameFaces = computed((): 6 | 8 => {
    const layout = this.gameState()?.sheetLayouts[this.playerId()];
    return (layout?.rows[0]?.cells.length ?? 11) > 11 ? 8 : 6;
  });

  scoreRows = computed(() => {
    const max = this.gameFaces() * 2;
    return Array.from({ length: max }, (_, i) => {
      const n = i + 1;
      return { crosses: n, points: n * (n + 1) / 2 };
    });
  });

  private bigPointsCap = computed((): number | null => {
    const layout = this.gameState()?.sheetLayouts[this.playerId()];
    if (!layout) return null;
    const hasBonus = layout.rows.some(row =>
      row.cells.some(c => c.tags?.some(t => t.type === CellTag.TypeEnum.SECONDARY_COLOR))
    );
    if (!hasBonus) return null;
    const regularRow = layout.rows.find(r => r.lock != null);
    return regularRow ? regularRow.cells.length + 4 : null;
  });

  maxedColors = computed((): Set<string> => {
    const cap = this.bigPointsCap();
    if (cap === null) return this.emptySet;
    const layout   = this.gameState()?.sheetLayouts[this.playerId()];
    const progress = this.gameState()?.sheetProgress[this.playerId()];
    if (!layout) return this.emptySet;

    const counts: Record<string, number> = {};
    for (const row of layout.rows) {
      const rowState = progress?.rowStates[row.id];
      if (!rowState) continue;
      const crossed = new Set(rowState.crossedCells ?? []);
      for (const cell of row.cells) {
        if (!crossed.has(cell.id)) continue;
        counts[cell.color] = (counts[cell.color] ?? 0) + 1;
        for (const tag of cell.tags ?? []) {
          if (tag.type === CellTag.TypeEnum.SECONDARY_COLOR && tag.secondaryColor) {
            counts[tag.secondaryColor] = (counts[tag.secondaryColor] ?? 0) + 1;
          }
        }
      }
      if (rowState.lockCrossed && row.lock) {
        counts[row.lock.color] = (counts[row.lock.color] ?? 0) + 1;
      }
    }

    const maxed = new Set<string>();
    for (const [color, count] of Object.entries(counts)) {
      if (count >= cap) maxed.add(color);
    }
    return maxed;
  });

  bonusNumbersFor(pid: string): number[] {
    return this.gameState()?.bonusNumbers?.[pid] ?? [];
  }

  isBonusNumberActive(pid: string, n: number): boolean {
    const roll = this.turnState()?.currentRoll;
    if (!roll) return false;
    return roll.white1 + roll.white2 === n;
  }

  coloredDiceEntries = computed(() => {
    const roll = this.turnState()?.currentRoll;
    const active = this.gameState()?.activeDiceColors ?? [];
    return active.map(color => ({ color, value: roll?.coloredDice[color] ?? null }));
  });

  clickableCellIds = computed((): Set<string> => {
    const state = this.gameState();
    const pid   = this.playerId();
    const turn  = this.turnState();
    if (!state || !turn?.currentRoll) return this.emptySet;

    const roll     = turn.currentRoll;
    const layout   = state.sheetLayouts[pid];
    const progress = state.sheetProgress[pid];
    if (!layout) return this.emptySet;

    const result    = new Set<string>();
    const closedRows = state.closedRows ?? {};

    if (turn.phase === TurnPhase.ACTIVE_MOVE && this.isMyTurn()) {
      const effectiveWW = turn.effectiveWhiteWhite?.[pid];
      if (!turn.whiteWhiteUsed && !turn.colorDieUsed) {
        const wwTarget = effectiveWW ?? (roll.white1 + roll.white2);
        this.collectCells(layout, progress, closedRows, wwTarget, null, result, effectiveWW != null);
      }
      if (!turn.colorDieUsed) {
        for (const row of layout.rows) {
          if (closedRows[row.id]) continue;
          const rowColor = row.cells[0]?.color as Color;
          const colorVal = roll.coloredDice[rowColor];
          if (colorVal == null) continue;
          this.collectCells(layout, progress, closedRows, roll.white1 + colorVal, row.id, result);
          if (roll.white2 !== roll.white1) {
            this.collectCells(layout, progress, closedRows, roll.white2 + colorVal, row.id, result);
          }
        }
      }
    } else if ((turn.phase === TurnPhase.PASSIVE_MOVE
                || turn.phase === TurnPhase.ACTIVE_MOVE)
               && this.isInPassiveQueue()) {
      const effectiveWW = turn.effectiveWhiteWhite?.[pid];
      // Allow WW cells when no pending cross, OR when the only pending cross is an x-change.
      if (this.pendingCellIds().size === 0 || effectiveWW != null) {
        const wwTarget = effectiveWW ?? (roll.white1 + roll.white2);
        this.collectCells(layout, progress, closedRows, wwTarget, null, result, effectiveWW != null);
      }
    }

    return result;
  });

  // Subset of clickableCellIds: cells reachable by the white+white combination.
  // These receive the golden glow; all other clickable cells receive the pulsating purple glow.
  whiteWhiteClickableCellIds = computed((): Set<string> => {
    if (this.rollingDice()) return this.emptySet;
    const state = this.gameState();
    const pid   = this.playerId();
    const turn  = this.turnState();
    if (!state || !turn?.currentRoll) return this.emptySet;

    const roll     = turn.currentRoll;
    const layout   = state.sheetLayouts[pid];
    const progress = state.sheetProgress[pid];
    if (!layout) return this.emptySet;

    const closedRows = state.closedRows ?? {};
    const result     = new Set<string>();

    if (turn.phase === TurnPhase.ACTIVE_MOVE && this.isMyTurn()) {
      const effectiveWW = turn.effectiveWhiteWhite?.[pid];
      if (!turn.whiteWhiteUsed && !turn.colorDieUsed) {
        const wwTarget = effectiveWW ?? (roll.white1 + roll.white2);
        this.collectCells(layout, progress, closedRows, wwTarget, null, result, effectiveWW != null);
      }
    } else if ((turn.phase === TurnPhase.PASSIVE_MOVE
                || turn.phase === TurnPhase.ACTIVE_MOVE)
               && this.isInPassiveQueue()) {
      const effectiveWW = turn.effectiveWhiteWhite?.[pid];
      if (this.pendingCellIds().size === 0 || effectiveWW != null) {
        const wwTarget = effectiveWW ?? (roll.white1 + roll.white2);
        this.collectCells(layout, progress, closedRows, wwTarget, null, result, effectiveWW != null);
      }
    }

    return result;
  });

  private collectCells(
    layout:         SheetLayout,
    progress:       SheetProgress | undefined,
    closedRows:     Record<string, string>,
    targetValue:    number,
    restrictRow:    string | null,
    result:         Set<string>,
    hasXChangeActive: boolean = false
  ) {
    const bonusNums: number[] = this.gameState()?.bonusNumbers?.[this.playerId()] ?? [];

    // Build a map from rowId -> Set of crossed displayValues for the bonus prerequisite check.
    const crossedValuesInRow = (rowId: string | undefined): Set<string> => {
      if (!rowId) return new Set();
      const rowLayout = layout.rows.find(r => r.id === rowId);
      if (!rowLayout) return new Set();
      const crossedIds = new Set(progress?.rowStates[rowId]?.crossedCells ?? []);
      return new Set(rowLayout.cells.filter(c => crossedIds.has(c.id)).map(c => c.displayValue));
    };

    for (const row of layout.rows) {
      if (restrictRow && row.id !== restrictRow) continue;
      if (closedRows[row.id]) continue;
      const crossed = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
      const lastPos = Math.max(-1, ...row.cells.filter(c => crossed.has(c.id)).map(c => c.position));
      for (const cell of row.cells) {
        if (crossed.has(cell.id)) continue;
        if (cell.position <= lastPos) continue;
        // X-change cells: match against their pair values instead of displayValue.
        const xchangeTag = cell.tags.find(t => t.type === CellTag.TypeEnum.X_CHANGE);
        if (xchangeTag) {
          if (hasXChangeActive) continue; // x-change already applied; don't offer another
          if (restrictRow) continue;       // x-change only available via white+white (no restrictRow)
          if (xchangeTag.valueA !== targetValue && xchangeTag.valueB !== targetValue) continue;
        } else if (parseInt(cell.displayValue) !== targetValue) continue;
        if (cell.closingEligible && row.lock) {
          const alreadyCrossedClosing = row.lock.closingCells.filter(id => crossed.has(id)).length;
          const normalCrossed = crossed.size - alreadyCrossedClosing;
          if (normalCrossed + 1 < row.lock.minCrosses) continue;
        }
        if (row.bonusRow) {
          const upperCrossed = crossedValuesInRow(row.upperNeighbourRowId);
          const lowerCrossed = crossedValuesInRow(row.lowerNeighbourRowId);
          if (!upperCrossed.has(cell.displayValue) && !lowerCrossed.has(cell.displayValue)) continue;
        }
        result.add(cell.id);
      }
    }

    // Longo bonus: when the white sum triggers a bonus number, also offer the leftmost
    // uncrossed cell in each row tied at fewest crosses. Only applies to unrestricted
    // (white+white) calls — color die calls always supply a restrictRow.
    if (!restrictRow && bonusNums.includes(targetValue)) {
      let fewest = Infinity;
      for (const row of layout.rows) {
        if (closedRows[row.id]) continue;
        const count = progress?.rowStates[row.id]?.crossedCells?.length ?? 0;
        if (count < fewest) fewest = count;
      }
      if (isFinite(fewest)) {
        for (const row of layout.rows) {
          if (closedRows[row.id]) continue;
          const crossed = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
          if (crossed.size !== fewest) continue;
          const lastPos = crossed.size > 0
            ? Math.max(...row.cells.filter(c => crossed.has(c.id)).map(c => c.position))
            : -1;
          const leftmost = row.cells
            .filter(c => !crossed.has(c.id) && c.position > lastPos)
            .sort((a, b) => a.position - b.position)[0];
          if (leftmost) result.add(leftmost.id);
        }
      }
    }
  }

  // ── Actions ────────────────────────────────────────────────────────────────

  sendRoll() {
    this.rollingDice.set(true);
    this.rollStartTime = Date.now();
    this.sendMove({ moveType: MoveType.ROLL });
  }

  passActive() {
    this.sendMove({ moveType: MoveType.PASS });
  }

  passPassive() {
    this.sendMoveAs(this.playerId(), { moveType: MoveType.PASS });
  }

  onCellClicked(rowId: string, cellId: string, ownerPid?: string) {
    if (this.isOffline()) {
      const pid = ownerPid ?? this.playerId();
      this.audio.play(AudioService.CROSS);
      this.sendMoveAs(pid, { moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId });
      return;
    }

    if (this.pendingCellIds().has(cellId)) {
      this.audio.play(AudioService.UNDO_CROSS);
      this.sendMove({ moveType: MoveType.RESET_TURN });
      return;
    }

    const state = this.gameState();
    const turn  = this.turnState();
    if (!state || !turn?.currentRoll) return;

    const pid    = this.playerId();
    const layout = state.sheetLayouts[pid];
    const row    = layout?.rows.find(r => r.id === rowId);
    const cell   = row?.cells.find(c => c.id === cellId);

    // LONGO: the second-to-last closing cell ("15"/"3") shows a YES/NO modal.
    // YES → cross the cell and send DECLARE_LOCK_INTENT immediately (notifies passives).
    // NO  → just cross the cell; no closure intent.
    // The last closing cell ("16"/"2") is auto-detected at EndTurn — no modal needed.
    if (row && cell?.closingEligible && (row.lock?.closingCells?.length ?? 0) > 1) {
      const closingCells   = row.lock!.closingCells;
      const secondToLastId = closingCells[closingCells.length - 2];
      if (cell.id === secondToLastId) {
        const req = this.buildCrossMoveRequest(row, cell, rowId, cellId);
        if (req) {
          const rowColor = (row.lock!.color ?? row.cells[0]?.color) as Color;
          this.rowClosureModal.showLockConfirm(
            rowColor,
            () => {
              // YES: cross the cell and queue DECLARE_LOCK_INTENT to fire once cross is applied.
              this.rowClosureModal.clearLockConfirm();
              this.pendingAutoLock.set({ rowId: row!.id, autoLock: true, cellId: cellId });
              this.audio.play(AudioService.CROSS);
              this.sendMove(req);
            },
            () => {
              // NO: just cross the cell, no closing intent.
              this.rowClosureModal.clearLockConfirm();
              this.audio.play(AudioService.CROSS);
              this.sendMove(req);
            }
          );
        }
        return;
      }
    }

    // Last (or only) eligible cell: set up auto-lock after the cross is applied.
    if (row && cell?.closingEligible && row.lock) {
      this.pendingAutoLock.set(this.computePendingAutoLock(row, cell));
    }

    // Passive players may only use white+white — skip move-type computation entirely.
    if (this.isInPassiveQueue()) {
      this.audio.play(AudioService.CROSS);
      this.sendMove({ moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId });
      return;
    }

    if (!cell || !row) return;

    // Longo bonus: white+white hit a bonus number so this cell is the leftmost in a
    // tied-fewest row. Always send as white+white — the colour die must not be consumed
    // even if the cell's display value also matches a white+colour combination.
    if (this.bonusCellIds().has(cellId)) {
      this.audio.play(AudioService.CROSS);
      this.sendMove({ moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId });
      return;
    }

    const req = this.buildCrossMoveRequest(row, cell, rowId, cellId);
    if (req) {
      this.audio.play(AudioService.CROSS);
      this.sendMove(req);
    } else if (this.clickableCellIds().has(cellId)) {
      // Cell is valid (e.g. Longo bonus cross) but its display value doesn't match
      // any dice combination directly — treat it as a white+white cross.
      this.audio.play(AudioService.CROSS);
      this.sendMove({ moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId });
    }
  }

  private buildCrossMoveRequest(row: SheetRow, cell: SheetCell, rowId: string, cellId: string): MoveRequest | null {
    if (this.isInPassiveQueue()) {
      return { moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId };
    }
    const turn = this.turnState();
    if (!turn?.currentRoll) return null;
    const roll       = turn.currentRoll;
    const pid        = this.playerId();
    const cellValue  = parseInt(cell.displayValue);
    const rowColor   = row.cells[0]?.color as Color;
    const colorVal   = roll.coloredDice[rowColor] ?? null;
    const effectiveWW = turn.effectiveWhiteWhite?.[pid];
    const wwTarget   = effectiveWW ?? (roll.white1 + roll.white2);
    const isWW    = cellValue === wwTarget && !turn.whiteWhiteUsed && !turn.colorDieUsed;
    const isColor = colorVal != null &&
      (cellValue === roll.white1 + colorVal || cellValue === roll.white2 + colorVal) &&
      !turn.colorDieUsed;
    if (!isWW && !isColor) return null;
    return { moveType: (isColor && !isWW) ? MoveType.CROSS_COLOR_DIE : MoveType.CROSS_WHITE_WHITE, rowId, cellId };
  }

  canTakePunishment(pid: string): boolean {
    const punishments = this.gameState()?.sheetProgress[pid]?.punishments ?? 0;
    if (punishments >= 4) return false;
    if (this.isOffline()) return true;
    return this.canGiveUp() && pid === this.playerId();
  }

  onPunishmentClicked(pid: string) {
    if (!this.canTakePunishment(pid)) return;
    if (this.isOffline()) {
      this.sendMoveAs(pid, { moveType: MoveType.TAKE_PUNISHMENT });
    } else {
      this.sendMove({ moveType: MoveType.GIVE_UP });
    }
  }

  offlineLock(pid: string, rowId: string) {
    this.sendMoveAs(pid, { moveType: MoveType.DECLARE_LOCK_INTENT, rowId });
  }

  onLockClicked(rowId: string, pid: string) {
    if (this.isOffline()) {
      this.offlineLock(pid, rowId);
    } else {
      this.sendMove({ moveType: MoveType.DECLARE_LOCK_INTENT, rowId });
    }
  }

  offlineClickableCellIds(pid: string): Set<string> {
    const state = this.gameState();
    if (!state) return this.emptySet;
    const layout   = state.sheetLayouts[pid];
    const progress = state.sheetProgress[pid];
    const closed   = state.closedRows ?? {};
    if (!layout) return this.emptySet;

    const result = new Set<string>();
    for (const row of layout.rows) {
      if (closed[row.id]) continue;
      const crossed  = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
      const lastPos  = Math.max(-1, ...row.cells.filter(c => crossed.has(c.id)).map(c => c.position));
      for (const cell of row.cells) {
        if (!crossed.has(cell.id) && cell.position > lastPos) result.add(cell.id);
      }
    }
    return result;
  }

  isLockEligible(pid: string, rowId: string): boolean {
    const state = this.gameState();
    if (!state) return false;
    const layout   = state.sheetLayouts[pid];
    const progress = state.sheetProgress[pid];
    if (!layout) return false;
    const row = layout.rows.find(r => r.id === rowId);
    if (!row?.lock) return false;
    const rowState = progress?.rowStates[rowId];
    if (rowState?.lockCrossed) return false;

    const permanent = new Set(rowState?.crossedCells ?? []);
    if (permanent.size < row.lock.minCrosses) return false;

    // Mirror LongoTurnRules.canCrossLock / StandardTurnRules.canCrossLock:
    // Any ONE closing cell (permanent or pending) qualifies for the lock.
    const pending  = this.pendingCellIds();
    const closing  = row.lock.closingCells;
    const lastCell = closing[closing.length - 1];

    // Last closing cell in any crosses (permanent or pending) → eligible.
    if (permanent.has(lastCell) || pending.has(lastCell)) return true;

    // Second-to-last closing cell enables locking only while it is a pending cross.
    if (closing.length > 1) {
      const secondLast = closing[closing.length - 2];
      return pending.has(secondLast);
    }
    return false;
  }

  private computePendingAutoLock(row: SheetRow, cell: SheetCell): { rowId: string; autoLock: boolean; cellId: string } | null {
    if (!this.wouldEnableLockDeclaration(row, cell.id)) return null;
    const closingCells  = row.lock!.closingCells;
    const lastClosingId = closingCells[closingCells.length - 1];
    const autoLock = cell.id === lastClosingId;
    return { rowId: row.id, autoLock, cellId: cell.id };
  }

  private wouldEnableLockDeclaration(row: SheetRow, newCellId: string): boolean {
    const state = this.gameState();
    if (!state || !row.lock) return false;
    const pid       = this.playerId();
    const permanent = new Set(state.sheetProgress[pid]?.rowStates[row.id]?.crossedCells ?? []);
    const pending   = new Set([...this.pendingCellIds(), newCellId]);
    const all       = new Set([...permanent, ...pending]);
    // Any ONE closing cell in all crosses is enough to qualify.
    return row.lock.closingCells.some(id => all.has(id));
  }

  private canDeclareLockForRow(s: GameState, rowId: string): boolean {
    const pid      = this.playerId();
    const row      = s.sheetLayouts?.[pid]?.rows.find(r => r.id === rowId);
    if (!row?.lock) return false;
    if ((s.closedRows ?? {})[rowId]) return false;
    if ((s.pendingClosures ?? {})[rowId]) return false; // already declared
    const rowState = s.sheetProgress?.[pid]?.rowStates?.[rowId];
    if (rowState?.lockCrossed) return false;
    const permanent = new Set(rowState?.crossedCells ?? []);
    const pending   = new Set(s.turnState?.pendingCrosses?.[pid] ?? []);
    const closing   = row.lock.closingCells;
    const last      = closing[closing.length - 1];

    // Last closing cell (permanent or pending) → can declare.
    if (permanent.has(last) || pending.has(last)) return true;

    // Second-to-last closing cell pending (Longo "15"/"3" YES scenario) → can declare.
    if (closing.length > 1) {
      const secondLast = closing[closing.length - 2];
      if (pending.has(secondLast)) {
        const allCount = permanent.size + 1; // pending adds 1
        return allCount >= row.lock.minCrosses;
      }
    }
    return false;
  }

  private checkPendingAutoLock(s: GameState) {
    const pending = this.pendingAutoLock();
    if (!pending) return;
    // A new turn or game-over means the cross was never persisted; discard.
    if (s.gameOver || s.turnState?.phase === TurnPhase.ROLL) {
      this.pendingAutoLock.set(null);
      return;
    }
    const { rowId, autoLock, cellId } = pending;

    // Clear stale pendingAutoLock when the triggering cross has been undone.
    const pid = this.playerId();
    const permanent    = new Set(s.sheetProgress?.[pid]?.rowStates?.[rowId]?.crossedCells ?? []);
    const pendingCrosses = new Set(s.turnState?.pendingCrosses?.[pid] ?? []);
    if (!permanent.has(cellId) && !pendingCrosses.has(cellId)) {
      this.pendingAutoLock.set(null);
      return;
    }

    if (!this.canDeclareLockForRow(s, rowId)) {
      // Triggering cell is present but not yet qualifying (e.g. Longo "15" crossed,
      // "16" not yet). Wait for the next state update.
      return;
    }

    this.pendingAutoLock.set(null);

    if (autoLock) {
      this.sendMove({ moveType: MoveType.DECLARE_LOCK_INTENT, rowId });
    }
  }

  private sendMoveAs(pid: string, req: MoveRequest) {
    if (this.moveSub && !this.moveSub.closed) {
      this.moveSub.unsubscribe();
      this.fetchState();
    }
    this.moveSub = this.movesService.makeMove(this.sessionId(), pid, req)
      .subscribe({
        next: () => this.fetchState(),
        error: e => {
          console.error('Move rejected:', e);
          this.fetchState();
        }
      });
  }

  private sendMove(req: MoveRequest) {
    if (this.moveSub && !this.moveSub.closed) {
      this.moveSub.unsubscribe();
      this.fetchState();
    }
    this.moveSub = this.movesService.makeMove(this.sessionId(), this.playerId(), req)
      .subscribe({
        next: (response) => {
          if (response?.botRolled) {
            this.rollingDice.set(true);
            this.rollStartTime = Date.now();
            setTimeout(() => this.fetchState(), this.ROLL_ANIM_MIN_MS);
          } else {
            this.fetchState();
          }
        },
        error: e => {
          this.rollingDice.set(false);
          console.error('Move rejected:', e);
          this.fetchState();
        }
      });
  }

  private applyState(s: GameState) {
    // Never let an out-of-order response overwrite a newer state.
    const curr = this.gameState()?.version;
    if (curr !== undefined && s.version !== undefined && s.version < curr) return;

    const prev = this.gameState();
    if (prev) {
      const prevPunishments = Object.values(prev.sheetProgress ?? {}).reduce((n, p) => n + (p.punishments ?? 0), 0);
      const newPunishments  = Object.values(s.sheetProgress   ?? {}).reduce((n, p) => n + (p.punishments ?? 0), 0);
      if (newPunishments > prevPunishments) this.audio.play(AudioService.PUNISHMENT);
    }

    // Track whether this player was re-queued mid-turn (e.g. active declared a lock intent
    // after the player had already passed). Excludes the normal ROLL→ACTIVE_MOVE join and
    // the initial page load (prev === null).
    const myId = this.playerId();
    const prevPhase = prev?.turnState?.phase;
    const wasInQueue = (prev?.turnState?.passivePlayerQueue ?? []).includes(myId);
    const isNowInQueue = (s.turnState?.passivePlayerQueue ?? []).includes(myId);
    if (prev !== null && !wasInQueue && isNowInQueue && prevPhase !== TurnPhase.ROLL) {
      this.reQueuedThisTurn.set(true);
    }
    if (s.turnState?.phase === TurnPhase.ROLL || s.gameOver) {
      this.reQueuedThisTurn.set(false);
    }

    const remaining = Math.max(0, this.ROLL_ANIM_MIN_MS - (Date.now() - this.rollStartTime));
    if (this.rollingDice() && this.isMyTurn()) {
      // Active player who rolled: delay showing the result until the animation finishes.
      setTimeout(() => {
        if ((s.version ?? 0) >= (this.gameState()?.version ?? -1)) {
          this.gameState.set(s);
        }
        this.audio.play(AudioService.DICE);
        this.rollingDice.set(false);
      }, remaining);
    } else {
      // Apply state immediately so dice area and values are visible right away.
      this.gameState.set(s);
      this.checkPendingAutoLock(s);
      // If a roll animation is in progress (passive player watching), clear it after the window.
      if (this.rollingDice()) {
        setTimeout(() => { this.audio.play(AudioService.DICE); this.rollingDice.set(false); }, remaining);
      }
    }
  }

  private fetchState() {
    // Cancel any in-flight state fetch so that only the most recent response wins.
    this.stateSub?.unsubscribe();
    this.stateSub = this.gameStatesService.getGameState(this.sessionId()).subscribe({
      next: (s: GameState) => this.applyState(s),
      error: () => { window.location.href = environment.lobbyUrl; }
    });
  }

  // ── View helpers ───────────────────────────────────────────────────────────

  layoutFor(pid: string): SheetLayout | null {
    return this.gameState()?.sheetLayouts?.[pid] ?? null;
  }

  // For each row in the current player's layout: pixel x-offsets of auto-cross
  // connections going up (to the row above) and down (to the row below).
  // Formula: 8px left-padding + position * (44px cell + 4px gap) + 22px half-width.
  // When the immediately adjacent row is a bonus row, we also look one step further
  // so that connections spanning across a bonus row are found correctly.
  myRowConnectors = computed((): Map<string, { above: number[], below: number[], hasBonusRowAbove: boolean, hasBonusRowBelow: boolean }> => {
    const layout = this.layoutFor(this.playerId());
    const result = new Map<string, { above: number[], below: number[], hasBonusRowAbove: boolean, hasBonusRowBelow: boolean }>();
    if (!layout) return result;
    for (let i = 0; i < layout.rows.length; i++) {
      const row = layout.rows[i];

      const rowBelow = layout.rows[i + 1];
      const hasBonusRowBelow = rowBelow?.bonusRow === true;
      const belowIds = new Set<string>(rowBelow?.cells.map(c => c.id) ?? []);
      if (hasBonusRowBelow && i + 2 < layout.rows.length) {
        for (const id of layout.rows[i + 2].cells.map(c => c.id)) belowIds.add(id);
      }

      const rowAbove = layout.rows[i - 1];
      const hasBonusRowAbove = rowAbove?.bonusRow === true;
      const aboveIds = new Set<string>(rowAbove?.cells.map(c => c.id) ?? []);
      if (hasBonusRowAbove && i - 2 >= 0) {
        for (const id of layout.rows[i - 2].cells.map(c => c.id)) aboveIds.add(id);
      }

      const above: number[] = [];
      const below: number[] = [];
      for (const cell of row.cells) {
        for (const tag of cell.tags ?? []) {
          if (tag.type !== CellTag.TypeEnum.AUTO_CROSS || !tag.target) continue;
          const offset = 8 + cell.position * 48 + 22;
          if (aboveIds.has(tag.target)) above.push(offset);
          if (belowIds.has(tag.target)) below.push(offset);
        }
      }
      result.set(row.id, { above, below, hasBonusRowAbove, hasBonusRowBelow });
    }
    return result;
  });

  rowStateFor(pid: string, rowId: string): RowState | null {
    return this.gameState()?.sheetProgress?.[pid]?.rowStates?.[rowId] ?? null;
  }

  isRowClosed(rowId: string): boolean {
    return rowId in (this.gameState()?.closedRows ?? {});
  }

  playerName(pid: string): string {
    return this.gameState()?.players.find(p => p.id === pid)?.name ?? pid;
  }

  onConfirmRowClosure() {
    if (this.hasPendingPassiveCross()) {
      this.sendMove({ moveType: MoveType.PASS });
    } else if (this.hasPendingActiveCross()) {
      // Active player acknowledges the notification and continues their turn.
      // They still have their pending cross(es) and can make more before clicking EndTurn.
      this.suppressModal.set(true);
    } else if (this.isMyTurn() && this.isInPassiveQueue()) {
      // Active player was re-queued for a final look before EVALUATE.
      // OK = proceed without reverting → EndTurn as passive to trigger EVALUATE.
      this.suppressModal.set(true);
      this.passPassive();
    } else {
      // Notification-only dismiss — player clicks the board PASS button or a cell when ready.
      this.suppressModal.set(true);
      this.rowClosureModal.clear();
    }
  }

  onChangeRowClosure() {
    if (this.hasPendingPassiveCross()) {
      // Passive player undoes their cross to reconsider.
      this.suppressModal.set(false);
      this.sendMove({ moveType: MoveType.UNDO_LAST_CROSS });
    } else if (this.hasPendingActiveCross()) {
      // Active player resets their entire turn to reconsider — suppress modal until
      // they make a new cross, at which point hasPendingActiveCross becomes true again
      // and the auto-unsuppress (pendingCellIds > 0) brings the modal back.
      this.suppressModal.set(true);
      this.sendMove({ moveType: MoveType.RESET_TURN });
    } else if (this.hasRevertableEndTurn() || this.hasRevertablePassiveEndTurn()) {
      // Active or passive player reverts their EndTurn to make a different/additional move.
      // Server restores the player's state and puts them back in the appropriate phase/queue.
      this.suppressModal.set(true);
      this.sendMove({ moveType: MoveType.RESET_TURN });
    } else if (this.canPassPassive() && this.reQueuedThisTurn()) {
      // Re-queued passive (already passed earlier this turn): Change = RESET_TURN so snapshot
      // is restored and they can make a fresh decision. Modal auto-unsuppresses on next cross.
      this.suppressModal.set(true);
      this.sendMove({ moveType: MoveType.RESET_TURN });
    } else {
      // Notification-only dismiss — player decides what to do on the board.
      this.suppressModal.set(true);
      this.rowClosureModal.clear();
    }
  }

  protected readonly DiceComponent = DiceComponent;
}
