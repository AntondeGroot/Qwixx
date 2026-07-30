import {
  AfterViewInit,
  Component,
  computed,
  effect,
  ElementRef,
  HostListener,
  inject,
  OnDestroy,
  OnInit,
  signal,
  untracked,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { environment } from '../../environments/environment';
// TODO(boundaries): move this data access behind an app service so the component doesn't inject the
// generated API layer directly. Existing debt — the rule forbids any NEW component → generated-api edge.
// eslint-disable-next-line boundaries/dependencies
import { GamestatesService, MovesService } from '../../generated/api/api';
import {
  AvailableMove,
  CellTag,
  Color,
  GameState,
  MoveRequest,
  MoveType,
  RowState,
  SheetLayout,
  TurnPhase,
} from '../../generated/model/models';
import { ConnectorOverlayComponent } from '../connector-overlay/connector-overlay.component';
import { DiceComponent } from '../dice/dice.component';
import { PlayerListComponent } from '../player-list/player-list.component';
import { SilverMarkComponent } from '../silver-mark/silver-mark.component';
import { connectorTargetIds } from '../connector-overlay/connector-links.util';
import { bonusKindOf, computeBonusBProgress } from '../row/bonus-b.util';
import { RowComponent } from '../row/row.component';
import { NoticeRequest, RowClosureModalService } from '../services/row-closure-modal.service';
import { AudioService } from '../services/audio.service';
import { RoomService } from '../services/room.service';
import { CellHighlightService } from '../services/cell-highlight.service';
import { AutoLockService } from '../services/auto-lock.service';
import { TranslateService } from '@ngx-translate/core';

@Component({
  selector: 'app-board',
  imports: [RowComponent, DiceComponent, PlayerListComponent, SilverMarkComponent, ConnectorOverlayComponent],
  templateUrl: './board.component.html',
  styleUrl: './board.component.css',
})
export class BoardComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly gameStatesService = inject(GamestatesService);
  private readonly movesService = inject(MovesService);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly rowClosureModal = inject(RowClosureModalService);
  private readonly audio = inject(AudioService);
  private readonly roomService = inject(RoomService);
  private readonly highlight = inject(CellHighlightService);
  private readonly autoLock = inject(AutoLockService);
  private readonly translate = inject(TranslateService);

  sessionId = signal('');
  playerId = signal('');
  gameState = signal<GameState | null>(null);
  error = signal<string | null>(null);
  rollingDice = signal(false);
  // True from the instant a move is sent until the next authoritative state arrives.
  // Drives the "move sent" greyed-out button state so play feels responsive even while
  // the server is busy (e.g. running bot turns) before the buttons update.
  movePending = signal(false);
  // True once the player has dismissed the current closure notice. Stays set until the declaration
  // clears, so a cross never re-shows the notice (ending a move is always a deliberate Pass press).
  private readonly suppressModal = signal(false);

  private eventSource?: EventSource;
  private moveSub?: Subscription;
  private stateSub?: Subscription;

  private rollStartTime = 0;

  // Fallback design height used before the game state has rendered.
  private readonly MOBILE_DESIGN_H = 541;
  private readonly ROLL_ANIM_MIN_MS = 2800;

  readonly emptySet = new Set<string>();
  readonly TurnPhase = TurnPhase;

  private gameOverNavigated = false;

  // Re-expose auto-lock state for the template
  readonly pendingAutoLockRowId = this.autoLock.pendingRowId;

  constructor() {
    // Sync modal state to the service so the modal renders at the root level,
    // outside the board's CSS transform (which would break position:fixed on mobile).
    effect(() => {
      const myName = this.playerName(this.playerId());
      const state = this.gameState();
      // Merge the two notice kinds (row closure + max-punishment game-end) into one list. Show
      // requests from OTHER players only — the declarant never sees their own notice.
      const requests: NoticeRequest[] = [
        ...(state?.closureNotifications ?? []).map((r): NoticeRequest => ({
          playerName: r.playerName,
          kind: 'closure',
          rowColor: r.rowColor,
        })),
        ...(state?.punishmentNotifications ?? []).map((r): NoticeRequest => ({
          playerName: r.playerName,
          kind: 'punishment',
        })),
      ].filter((r) => r.playerName !== myName);

      if (requests.length === 0) {
        // No pending closure from others — reset suppression so the next intent shows fresh.
        untracked(() => this.suppressModal.set(false));
        this.rowClosureModal.clear();
        return;
      }

      const inQueue = this.isInPassiveQueue();
      const canRevert = this.canRevertEndTurn();
      // Suppress while a self-close YES/NO confirm is mid-flight (Longo second-to-last cell), or once
      // the player has dismissed this declaration. A cross NEVER re-shows the notice — ending a move
      // is always a deliberate Pass press, never inferred from a pending cross.
      const lockConfirmInProgress = inQueue && this.autoLock.pending() !== null;
      if (lockConfirmInProgress || this.suppressModal()) {
        this.rowClosureModal.clear();
      } else {
        const wasHidden = untracked(() => this.rowClosureModal.requests().length === 0);
        this.rowClosureModal.show(
          requests,
          () => this.onConfirmRowClosure(), // [Pass]
          () => this.onDismissRowClosure(), // [Make a move] / [OK]
          () => this.onRevertRowClosure(), // [Undo]
          inQueue, // canAct: a recipient still in the passive queue can cross and pass
          canRevert, // canRevert: already ended their turn but can revert to react
        );
        if (wasHidden) this.audio.play(AudioService.ROW_CLOSURE_BELL);
      }
    });

    effect(() => {
      if (this.gameState()?.gameOver && !this.gameOverNavigated) {
        this.gameOverNavigated = true;
        setTimeout(() => {
          void this.router.navigate(['/score', this.sessionId()], {
            queryParams: { pid: this.playerId() },
          });
        }, 1500);
      }
    });

    // Re-measure after every game-state render so Longo's bonus chips (80px each)
    // are accounted for — the static MOBILE_DESIGN_H only fits the standard sheet.
    effect(() => {
      this.gameState(); // depend so we re-run when state arrives
      untracked(() => setTimeout(() => this.applyMobileScale(), 0));
    });
  }

  ngOnInit() {
    const sid = this.route.snapshot.paramMap.get('sessionId') ?? '';
    const pid = this.route.snapshot.paramMap.get('playerId') ?? '';
    const rid = this.route.snapshot.queryParamMap.get('roomid') ?? null;
    this.sessionId.set(sid);
    this.playerId.set(pid);
    this.roomService.setGame(sid, pid, rid);
    this.fetchState();
    this.setupSse(sid);
  }

  ngAfterViewInit() {
    this.applyMobileScale();
  }

  @HostListener('window:resize')
  applyMobileScale() {
    const el = this.host.nativeElement as HTMLElement;
    const isPortrait = window.innerHeight > window.innerWidth;
    const layout = el.querySelector('.board-layout') as HTMLElement | null;
    if (!layout) {
      // Game state not yet rendered — use the fallback constant (portrait only).
      el.style.setProperty(
        '--mobile-scale',
        isPortrait ? Math.min((window.innerWidth - 16) / this.MOBILE_DESIGN_H, 1).toFixed(4) : '1',
      );
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
    let scale: number;
    if (isPortrait) {
      // Board is rotated 90°: DOM height → visual width, DOM width → visual height.
      // scaleH: fit the board's DOM height into the viewport's width (short side).
      // scaleW: fit the board's DOM width into the viewport's height (long side) —
      //         needed when wide variants (e.g. Longo) exceed 100dvh.
      const scaleH = h > 0 ? (window.innerWidth - 16) / h : (window.innerWidth - 16) / this.MOBILE_DESIGN_H;
      const scaleW = w > 0 ? (window.innerHeight - 16) / w : 1;
      scale = Math.min(scaleH, scaleW, 1);
    } else {
      // Landscape (or desktop): DOM dimensions map directly to visual dimensions.
      // Only zoom when the content overflows — on a large desktop Math.min clips to 1.
      const scaleH = h > 0 ? (window.innerHeight - 16) / h : 1;
      const scaleW = w > 0 ? (window.innerWidth - 16) / w : 1;
      scale = Math.min(scaleH, scaleW, 1);
    }
    el.style.setProperty('--mobile-scale', scale.toFixed(4));
  }

  ngOnDestroy() {
    this.eventSource?.close();
    this.moveSub?.unsubscribe();
    this.stateSub?.unsubscribe();
    this.rowClosureModal.clear();
    this.autoLock.clear();
  }

  private setupSse(sessionId: string): void {
    this.eventSource?.close();
    const es = new EventSource(`${environment.apiBaseUrl}/gamestates/${sessionId}/stream`);
    this.eventSource = es;

    es.onmessage = (event: MessageEvent) => {
      const s: GameState = JSON.parse(event.data);
      if (s.version !== this.gameState()?.version) {
        const prevRoll = this.gameState()?.turnState?.currentRoll;
        const newRoll = s.turnState?.currentRoll;
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

  isOffline = computed(() => this.gameState() !== null && this.gameState()!.turnState == null);

  isMyTurn = computed(() => this.turnState()?.activePlayerId === this.playerId());

  isInPassiveQueue = computed(() => (this.turnState()?.passivePlayerQueue ?? []).includes(this.playerId()));

  canRoll = computed(() => this.isMyTurn() && this.turnState()?.phase === TurnPhase.ROLL);

  canPassActive = computed(() => {
    const turn = this.turnState();
    if (!this.isMyTurn() || turn?.phase !== TurnPhase.ACTIVE_MOVE) return false;
    if (turn.whiteWhiteUsed === true || turn.colorDieUsed === true || turn.luckyNumberUsed === true) return true;
    // Allow EndTurn when this player has a pending lock-closure intent (declared without dice).
    const pid = this.playerId();
    const pendingClosures: Record<string, string[]> = this.gameState()?.pendingClosures ?? {};
    return Object.values(pendingClosures).some((ids) => ids.includes(pid));
  });

  canGiveUp = computed(() => {
    const turn = this.turnState();
    return (
      this.isMyTurn() &&
      turn?.phase === TurnPhase.ACTIVE_MOVE &&
      !turn.whiteWhiteUsed &&
      !turn.colorDieUsed &&
      !turn.luckyNumberUsed
    );
  });

  // Drives the board's checkmark Pass button for a passive who has crossed (board template),
  // and mustPassPassive. Unrelated to the closure notice, which never keys off pending crosses.
  hasPendingPassiveCross = computed(
    () => this.isInPassiveQueue() && !this.isMyTurn() && this.pendingCellIds().size > 0,
  );

  // A recipient who already ended their turn but could revert it to react to a newly-declared
  // closure — the active player now in PASSIVE_MOVE, or a passive who already left the queue while
  // others are still acting. RESET_TURN puts them back so they can cross. (When they still have a
  // pending cross they can also just re-click it; this covers the case where they have none.)
  canRevertEndTurn = computed(() => {
    const turn = this.turnState();
    const phase = turn?.phase;
    const queueNotEmpty = (turn?.passivePlayerQueue?.length ?? 0) > 0;
    if (this.isInPassiveQueue()) return false; // still in the queue — not a revert case
    if (this.isMyTurn()) return phase === TurnPhase.PASSIVE_MOVE && queueNotEmpty;
    return (phase === TurnPhase.ACTIVE_MOVE || phase === TurnPhase.PASSIVE_MOVE) && queueNotEmpty;
  });

  // True when the current player's layout contains Lucky Cross fields.
  hasLuckyCross = computed(() => {
    const layout = this.layoutFor(this.playerId());
    return !!layout?.rows.some((r) => r.cells.some((c) => c.tags.some((t) => t.type === CellTag.TypeEnum.LUCKY_CROSS)));
  });

  hasLuckyNumberRow = computed(() => {
    const layout = this.layoutFor(this.playerId());
    return !!layout?.rows.some((r) => r.luckyRow);
  });

  // Bonus B: how many of each kind's two boxes this player has crossed (for the strip's N/2 counter).
  bonusBProgress = computed(() =>
    computeBonusBProgress(this.layoutFor(this.playerId()), this.gameState()?.sheetProgress[this.playerId()]),
  );

  // True once this player has completed the Bonus B "no penalty" pair (its strip indicator is
  // crossed): mis-rolls no longer subtract, so the penalty display should read 0.
  noPenaltyAchieved = computed(() => {
    const state = this.gameState();
    const layout = this.layoutFor(this.playerId());
    if (!state || !layout) return false;
    const progress = state.sheetProgress[this.playerId()];
    for (const row of layout.rows) {
      if (!row.bonusBStrip) continue;
      const crossed = progress?.rowStates[row.id]?.crossedCells ?? [];
      for (const cell of row.cells) {
        if (bonusKindOf(cell) === CellTag.BonusKindEnum.NO_PENALTY && crossed.includes(cell.id)) return true;
      }
    }
    return false;
  });

  // True when this player has declared a lock intent that is currently pending.
  isDeclarantInLockPending = computed(() => {
    const myName = this.playerName(this.playerId());
    return (this.gameState()?.closureNotifications ?? []).some((r) => r.playerName === myName);
  });

  // Every pending closure declaration (including this player's own), shown as a persistent line
  // under the turn label: the notice modal is dismissable, so without it players lose track of
  // which row is about to close.
  pendingClosureNotices = computed(() => this.gameState()?.closureNotifications ?? []);

  rowColorClass(color: Color): string {
    return `cell-${color.toLowerCase()}`;
  }

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
      const row = layout.rows.find((r) => r.lock?.color === req.rowColor);
      if (row) result.add(row.id);
    }
    return result;
  });

  canPassPassive = computed(() => {
    const phase = this.turnState()?.phase;
    return this.isInPassiveQueue() && (phase === TurnPhase.PASSIVE_MOVE || phase === TurnPhase.ACTIVE_MOVE);
  });

  // True when the active player has no clickable cells and can only take a punishment.
  // pendingCellIds check: after an x-change cross the player can still RESET_TURN.
  mustGiveUp = computed(
    () =>
      !this.rollingDice() &&
      this.canGiveUp() &&
      !this.canPassActive() &&
      this.pendingCellIds().size === 0 &&
      this.clickableCellIds().size === 0,
  );

  // True when a passive player has no cells to cross and passing is their only option.
  mustPassPassive = computed(
    () =>
      !this.rollingDice() &&
      this.canPassPassive() &&
      !this.hasPendingPassiveCross() &&
      this.clickableCellIds().size === 0,
  );

  gameFaces = computed((): 6 | 8 => {
    const layout = this.gameState()?.sheetLayouts[this.playerId()];
    return (layout?.rows[0]?.cells.length ?? 11) > 11 ? 8 : 6;
  });

  scoreRows = computed(() => {
    const max = this.gameFaces() * 2;
    return Array.from({ length: max }, (_, i) => {
      const n = i + 1;
      return { crosses: n, points: (n * (n + 1)) / 2 };
    });
  });

  // Crosses required in a row before its lock cell can be crossed (5 standard, 6 Longo).
  // The server's minCrosses counts the lock cross itself, so subtract 1 for the display.
  // Null when the current sheet has no lockable rows, so the legend is hidden.
  lockCrossesRequired = computed((): number | null => {
    const layout = this.gameState()?.sheetLayouts[this.playerId()];
    const min = layout?.rows.find((r) => r.lock)?.lock?.minCrosses;
    return min == null ? null : min - 1;
  });

  pendingCellIds = computed(() => {
    const ids = this.gameState()?.turnState?.pendingCrosses?.[this.playerId()] ?? [];
    return new Set<string>(ids);
  });

  // ── Cell highlight computed signals (driven by server-provided availableMoves) ─────

  private readonly myAvailableMoves = computed((): AvailableMove[] => {
    return this.gameState()?.availableMoves?.[this.playerId()] ?? [];
  });

  clickableCellIds = computed((): Set<string> => {
    const moves = this.myAvailableMoves();
    if (moves.length === 0) return this.emptySet;
    return new Set(moves.map((m) => m.cellId));
  });

  whiteWhiteClickableCellIds = computed((): Set<string> => {
    if (this.rollingDice()) return this.emptySet;
    const moves = this.myAvailableMoves();
    if (moves.length === 0) return this.emptySet;
    return new Set(
      moves
        .filter((m) => m.moveType === MoveType.CROSS_WHITE_WHITE || m.moveType === MoveType.CROSS_LUCKY_CROSS)
        .map((m) => m.cellId),
    );
  });

  visibleClickableCellIds = computed((): Set<string> => (this.rollingDice() ? this.emptySet : this.clickableCellIds()));

  maxedColors = computed((): Set<string> => {
    const state = this.gameState();
    if (!state) return this.emptySet;
    return this.highlight.maxedColors(state, this.playerId());
  });

  coloredDiceEntries = computed(() => {
    const roll = this.turnState()?.currentRoll;
    const active = this.gameState()?.activeDiceColors ?? [];
    return active.map((color) => ({ color, value: roll?.coloredDice[color] ?? null }));
  });

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
    const turn = this.turnState();
    if (!state || !turn?.currentRoll) return;

    const pid = this.playerId();
    const layout = state.sheetLayouts[pid];
    const row = layout?.rows.find((r) => r.id === rowId);
    const cell = row?.cells.find((c) => c.id === cellId);

    // Resolve the move type from server-provided available moves (WW preferred over color die).
    const moveType = this.resolveMoveType(cellId);
    if (!moveType) return;

    // LONGO: the second-to-last closing cell ("15"/"3") shows a YES/NO modal.
    // YES → cross the cell and send DECLARE_LOCK_INTENT immediately (notifies passives).
    // NO  → just cross the cell; no closure intent.
    // The last closing cell ("16"/"2") is auto-detected at EndTurn — no modal needed.
    if (row && cell?.closingEligible && (row.lock?.closingCells?.length ?? 0) > 1) {
      const closingCells = row.lock!.closingCells;
      const secondToLastId = closingCells[closingCells.length - 2];
      if (cell.id === secondToLastId) {
        const rowColor = (row.lock!.color ?? row.cells[0]?.color) as Color;
        this.rowClosureModal.showLockConfirm(
          rowColor,
          () => {
            // YES: cross the cell and queue DECLARE_LOCK_INTENT to fire once cross is applied.
            this.rowClosureModal.clearLockConfirm();
            this.autoLock.pending.set({ rowId: row!.id, autoLock: true, cellId: cellId });
            this.audio.play(AudioService.CROSS);
            this.sendMove({ moveType, rowId, cellId });
          },
          () => {
            // NO: just cross the cell, no closing intent.
            this.rowClosureModal.clearLockConfirm();
            this.audio.play(AudioService.CROSS);
            this.sendMove({ moveType, rowId, cellId });
          },
        );
        return;
      }
    }

    // Last (or only) eligible cell: set up auto-lock after the cross is applied.
    if (row && cell?.closingEligible && row.lock) {
      this.autoLock.setupIfEligible(row, cell, state, pid, this.pendingCellIds());
    }

    this.audio.play(AudioService.CROSS);
    this.sendMove({ moveType, rowId, cellId });
  }

  private resolveMoveType(cellId: string): MoveType | null {
    const moves = this.myAvailableMoves();
    const forCell = moves.filter((m) => m.cellId === cellId);
    return (
      forCell.find((m) => m.moveType === MoveType.CROSS_LUCKY_CROSS)?.moveType ??
      forCell.find((m) => m.moveType === MoveType.CROSS_WHITE_WHITE)?.moveType ??
      forCell.find((m) => m.moveType === MoveType.CROSS_COLOR_DIE)?.moveType ??
      null
    );
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

  onLockClicked(rowId: string, pid: string) {
    if (this.isOffline()) {
      this.sendMoveAs(pid, { moveType: MoveType.DECLARE_LOCK_INTENT, rowId });
    } else {
      this.sendMove({ moveType: MoveType.DECLARE_LOCK_INTENT, rowId });
    }
  }

  offlineClickableCellIds(pid: string): Set<string> {
    const state = this.gameState();
    if (!state) return this.emptySet;
    return this.highlight.offlineClickable(state, pid);
  }

  isLockEligible(pid: string, rowId: string): boolean {
    const state = this.gameState();
    if (!state) return false;
    const layout = state.sheetLayouts[pid];
    const progress = state.sheetProgress[pid];
    if (!layout) return false;
    const row = layout.rows.find((r) => r.id === rowId);
    if (!row?.lock) return false;
    const rowState = progress?.rowStates[rowId];
    if (rowState?.lockCrossed) return false;

    const permanent = new Set(rowState?.crossedCells ?? []);
    if (permanent.size < row.lock.minCrosses) return false;

    // Mirror LongoTurnRules.canCrossLock / StandardTurnRules.canCrossLock:
    // Any ONE closing cell (permanent or pending) qualifies for the lock.
    const pending = this.pendingCellIds();
    const closing = row.lock.closingCells;
    const lastCell = closing[closing.length - 1];
    if (lastCell === undefined) return false; // no closing cells → not lock-eligible

    // Last closing cell in any crosses (permanent or pending) → eligible.
    if (permanent.has(lastCell) || pending.has(lastCell)) return true;

    // Second-to-last closing cell enables locking only while it is a pending cross.
    if (closing.length > 1) {
      const secondLast = closing[closing.length - 2]!; // length > 1 guarantees this index
      return pending.has(secondLast);
    }
    return false;
  }

  private sendMoveAs(pid: string, req: MoveRequest) {
    if (this.moveSub && !this.moveSub.closed) {
      this.moveSub.unsubscribe();
      this.fetchState();
    }
    this.movePending.set(true);
    this.moveSub = this.movesService.makeMove(this.sessionId(), pid, req).subscribe({
      next: () => this.fetchState(),
      error: (e) => {
        this.movePending.set(false);
        console.error('Move rejected:', e);
        this.fetchState();
      },
    });
  }

  private sendMove(req: MoveRequest) {
    if (this.moveSub && !this.moveSub.closed) {
      this.moveSub.unsubscribe();
      this.fetchState();
    }
    this.movePending.set(true);
    this.moveSub = this.movesService.makeMove(this.sessionId(), this.playerId(), req).subscribe({
      next: () => {
        // A bot roll triggered by this move animates via the SSE no-roll -> roll
        // transition (see setupSse), so we just sync the final state here.
        this.fetchState();
      },
      error: (e) => {
        this.rollingDice.set(false);
        this.movePending.set(false);
        console.error('Move rejected:', e);
        this.fetchState();
      },
    });
  }

  private applyState(s: GameState) {
    // Never let an out-of-order response overwrite a newer state.
    const curr = this.gameState()?.version;
    if (curr !== undefined && s.version < curr) return;

    // A newer authoritative state has arrived, so any in-flight move is now resolved.
    this.movePending.set(false);

    const prev = this.gameState();
    if (prev) {
      if (this.highlight.newPunishmentTaken(prev, s)) this.audio.play(AudioService.PUNISHMENT);
      if (this.highlight.crossedOwnLock(prev, s, this.playerId())) this.audio.play(AudioService.LOCK);
      if (this.highlight.bonusBJustCompleted(prev, s, this.playerId())) this.audio.play(AudioService.BONUS_B_COMPLETE);
      if (this.highlight.justCrossedBonusBox(prev, s, this.playerId())) this.audio.play(AudioService.BONUS);
    }

    const remaining = Math.max(0, this.ROLL_ANIM_MIN_MS - (Date.now() - this.rollStartTime));
    if (this.rollingDice() && this.isMyTurn()) {
      // Active player who rolled: delay showing the result until the animation finishes.
      setTimeout(() => {
        if ((s.version ?? 0) >= (this.gameState()?.version ?? -1)) {
          this.gameState.set(s);
        }
        this.settleDice();
      }, remaining);
    } else {
      // Apply state immediately so dice area and values are visible right away.
      this.gameState.set(s);
      const lockMove = this.autoLock.checkAndConsume(s, this.playerId());
      if (lockMove) this.sendMove(lockMove);
      // If a roll animation is in progress (passive player watching), clear it after the window.
      if (this.rollingDice()) setTimeout(() => this.settleDice(), remaining);
    }
  }

  // Ties the roll sounds to the client's own animation, not to SSE push count: plays once, on the
  // true→false transition that actually stops the animation (later duplicate settles for the same
  // roll find it already stopped). The bonus is personal — hasCrossableBonus is scoped to this player,
  // so it fires for their own bonus (their Longo number came up, or they can cross a bonus), whoever rolled.
  private settleDice(): void {
    if (!this.rollingDice()) return;
    this.rollingDice.set(false);
    this.audio.play(AudioService.DICE);
    if (this.highlight.hasCrossableBonus(this.gameState(), this.playerId())) this.audio.play(AudioService.BONUS);
  }

  private fetchState() {
    // Cancel any in-flight state fetch so that only the most recent response wins.
    this.stateSub?.unsubscribe();
    this.stateSub = this.gameStatesService.getGameState(this.sessionId()).subscribe({
      next: (s: GameState) => this.applyState(s),
      error: () => {
        window.location.href = environment.lobbyUrl;
      },
    });
  }

  // ── View helpers ───────────────────────────────────────────────────────────

  layoutFor(pid: string): SheetLayout | null {
    return this.gameState()?.sheetLayouts?.[pid] ?? null;
  }

  // Ids of the cells a Connected B (one-way) arrow points at — they get the dotted target ring via a
  // CSS pseudo-element on the cell itself, so it always sits exactly on the cell in every view. The
  // arrow lines themselves are drawn by ConnectorOverlayComponent, which measures them from the DOM.
  autoCrossTargetCellIds = computed((): Set<string> => {
    const layout = this.layoutFor(this.playerId());
    return layout ? connectorTargetIds(layout) : new Set<string>();
  });

  rowStateFor(pid: string, rowId: string): RowState | null {
    return this.gameState()?.sheetProgress?.[pid]?.rowStates?.[rowId] ?? null;
  }

  isRowClosed(rowId: string): boolean {
    return rowId in (this.gameState()?.closedRows ?? {});
  }

  playerName(pid: string): string {
    return this.gameState()?.players.find((p) => p.id === pid)?.name ?? pid;
  }

  bonusNumbersFor(pid: string): number[] {
    return this.gameState()?.bonusNumbers?.[pid] ?? [];
  }

  isBonusNumberActive(_pid: string, n: number): boolean {
    // Don't reveal the highlight until the dice animation has finished. For a
    // passive watcher the game state (and thus currentRoll) is applied while the
    // dice are still spinning, so gate on rollingDice() to avoid showing the
    // bonus mid-animation.
    if (this.rollingDice()) return false;
    const roll = this.turnState()?.currentRoll;
    if (!roll) return false;
    return roll.white1 + roll.white2 === n;
  }

  // [Pass] — a deliberate end of the player's reaction. Only offered to a recipient who can still
  // act (in the passive queue); it never fires from having made a cross.
  onConfirmRowClosure() {
    if (this.isInPassiveQueue()) this.passPassive();
    this.suppressModal.set(true);
    this.rowClosureModal.clear();
  }

  // [Make a move] / [OK] — hide the notice so the player can cross (or simply acknowledge it). The
  // notice will NOT re-appear when they make a cross; they end their turn with the board's Pass
  // button (or, while the notice is up, its [Pass]). Undo a cross by re-clicking it (RESET_TURN).
  onDismissRowClosure() {
    this.suppressModal.set(true);
    this.rowClosureModal.clear();
  }

  // [Undo] — a deliberate revert for a recipient who already ended their turn (and has no pending
  // cross to re-click): RESET_TURN puts them back in the queue / into ACTIVE_MOVE so they can react.
  onRevertRowClosure() {
    this.suppressModal.set(true);
    this.sendMove({ moveType: MoveType.RESET_TURN });
  }

  t(key: string, params?: object): string {
    return this.translate.instant(key, params);
  }

  protected readonly DiceComponent = DiceComponent;
}
