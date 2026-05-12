import { AfterViewInit, Component, computed, effect, ElementRef, HostListener, inject, OnDestroy, OnInit, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { interval, Subscription, switchMap } from 'rxjs';
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
import { RowClosureRequest } from '../row-closure-modal/row-closure-modal.component';
import { RowClosureModalService } from '../services/row-closure-modal.service';

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

  sessionId   = signal('');
  playerId    = signal('');
  gameState   = signal<GameState | null>(null);
  error       = signal<string | null>(null);
  rollingDice = signal(false);
  // True while the player dismissed the lock-intent modal to pick a new cell.
  // Suppresses the modal until they cross something (pendingCellIds becomes non-empty).
  private readonly suppressModal = signal(false);

  private pollSub?: Subscription;
  private moveSub?: Subscription;
  private stateSub?: Subscription;

  // Sync modal state to the service so the modal renders at the root level,
  // outside the board's CSS transform (which would break position:fixed on mobile).
  private readonly _modalSync = effect(() => {
    const hasAcknowledged = (this.turnState()?.lockAcknowledged ?? []).includes(this.playerId());
    const requests = (this.isInPassiveQueue() && !hasAcknowledged)
      ? (this.gameState()?.rowClosureRequests ?? [])
      : [];

    if (requests.length === 0) {
      // No lock pending — reset suppression so the next lock intent shows fresh.
      untracked(() => this.suppressModal.set(false));
      this.rowClosureModal.clear();
      return;
    }

    // Suppress the modal while the player is choosing a new cell (no pending cross yet).
    // Auto-unsuppress once they cross something.
    const suppress = this.suppressModal() && this.pendingCellIds().size === 0;
    if (suppress) {
      this.rowClosureModal.clear();
    } else {
      this.rowClosureModal.show(
        requests,
        () => this.onConfirmRowClosure(),
        () => this.onChangeRowClosure(),
        this.hasPendingPassiveCross()
      );
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

    this.pollSub = interval(2000).pipe(
      switchMap(() => this.gameStatesService.getGameState(sid))
    ).subscribe({
      next: (s: GameState) => {
        if (s.version !== this.gameState()?.version) {
          // If the active player just rolled (currentRoll appeared) and we are a
          // passive observer, start our own dice animation so the roll feels live.
          const prevRoll = this.gameState()?.turnState?.currentRoll;
          const newRoll  = s.turnState?.currentRoll;
          if (this.gameState() !== null && !prevRoll && newRoll) {
            this.rollingDice.set(true);
            this.rollStartTime = Date.now();
          }
          this.applyState(s);
        }
      },
      error: () => {}
    });
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
    this.pollSub?.unsubscribe();
    this.moveSub?.unsubscribe();
    this.stateSub?.unsubscribe();
    this.rowClosureModal.clear();
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
    return this.isMyTurn()
      && turn?.phase === TurnPhase.ACTIVE_MOVE
      && (turn.whiteWhiteUsed === true || turn.colorDieUsed === true);
  });

  canGiveUp = computed(() => {
    const turn = this.turnState();
    return this.isMyTurn()
      && turn?.phase === TurnPhase.ACTIVE_MOVE
      && !turn.whiteWhiteUsed
      && !turn.colorDieUsed;
  });

  hasPendingPassiveCross = computed(() =>
    this.isInPassiveQueue() && this.pendingCellIds().size > 0
  );

  // True when this player has declared a lock intent that is currently pending.
  // Derived from rowClosureRequests so it works even when no cell was crossed this turn
  // (e.g. the player clicked the lock button directly).
  isDeclarantInLockPending = computed(() => {
    const myName = this.playerName(this.playerId());
    return (this.gameState()?.rowClosureRequests ?? []).some(r => r.playerName === myName);
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
    for (const req of state.rowClosureRequests ?? []) {
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

  // True when the active declarant in LOCK_PENDING has crossed a second closing cell
  // and should confirm it via the green button rather than having it auto-apply.
  canConfirmDeclarantLock = computed(() => {
    const pending = this.pendingAutoLock();
    if (!pending?.autoLock) return false;
    if (this.turnState()?.phase !== TurnPhase.LOCK_PENDING) return false;
    const s = this.gameState();
    return !!s && this.isDeclarantInLockPending() && this.canDeclareLockForRow(s, pending.rowId);
  });

  confirmDeclarantLock() {
    const pending = this.pendingAutoLock();
    if (!pending) return;
    this.pendingAutoLock.set(null);
    this.sendMove({ moveType: MoveType.DECLARE_LOCK_INTENT, rowId: pending.rowId });
  }

  canPassPassive = computed(() => {
    const phase = this.turnState()?.phase;
    return this.isInPassiveQueue()
      && (phase === TurnPhase.PASSIVE_MOVE
          || phase === TurnPhase.ACTIVE_MOVE
          || phase === TurnPhase.LOCK_PENDING);
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
      if (!turn.whiteWhiteUsed && !turn.colorDieUsed) {
        this.collectCells(layout, progress, closedRows, roll.white1 + roll.white2, null, result);
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
    } else if (turn.phase === TurnPhase.LOCK_PENDING && this.isMyTurn()
               && turn.whiteWhiteUsed && !turn.colorDieUsed) {
      // Active declarant used white+white to close the first row. They may still use
      // their colored die to close a second row in the same turn.
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
    } else if ((turn.phase === TurnPhase.PASSIVE_MOVE
                || turn.phase === TurnPhase.ACTIVE_MOVE
                || turn.phase === TurnPhase.LOCK_PENDING)
               && this.isInPassiveQueue()
               && this.pendingCellIds().size === 0) {
      // Only offer cells before the passive player has made their one allowed cross.
      // LOCK_PENDING is included because declaring lock re-invites all passives to
      // reconsider crossing before the row closes.
      this.collectCells(layout, progress, closedRows, roll.white1 + roll.white2, null, result);
    }

    return result;
  });

  // Subset of clickableCellIds: cells reachable specifically by the white+white combination.
  // Used to show 🎲 on those cells so the player knows to use both white dice.
  // All other clickable cells (color die) show the row's colored circle emoji instead.
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
      if (!turn.whiteWhiteUsed && !turn.colorDieUsed) {
        this.collectCells(layout, progress, closedRows, roll.white1 + roll.white2, null, result);
      }
    } else if ((turn.phase === TurnPhase.PASSIVE_MOVE
                || turn.phase === TurnPhase.ACTIVE_MOVE
                || turn.phase === TurnPhase.LOCK_PENDING)
               && this.isInPassiveQueue()
               && this.pendingCellIds().size === 0) {
      this.collectCells(layout, progress, closedRows, roll.white1 + roll.white2, null, result);
    }

    return result;
  });

  private collectCells(
    layout:      SheetLayout,
    progress:    SheetProgress | undefined,
    closedRows:  Record<string, string>,
    targetValue: number,
    restrictRow: string | null,
    result:      Set<string>
  ) {
    const bonusNums: number[] = this.gameState()?.bonusNumbers?.[this.playerId()] ?? [];
    for (const row of layout.rows) {
      if (restrictRow && row.id !== restrictRow) continue;
      if (closedRows[row.id]) continue;
      const crossed = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
      const lastPos = Math.max(-1, ...row.cells.filter(c => crossed.has(c.id)).map(c => c.position));
      for (const cell of row.cells) {
        if (crossed.has(cell.id)) continue;
        if (cell.position <= lastPos) continue;
        if (parseInt(cell.displayValue) !== targetValue) continue;
        if (cell.closingEligible && row.lock) {
          const alreadyCrossedRequired = row.lock.requiredCells.filter(id => crossed.has(id)).length;
          const normalCrossed = crossed.size - alreadyCrossedRequired;
          if (normalCrossed + 1 < row.lock.minCrosses) continue;
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
      this.sendMoveAs(pid, { moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId });
      return;
    }

    if (this.pendingCellIds().has(cellId)) {
      // In LOCK_PENDING, UNDO_LAST_CROSS is the right action for everyone:
      // - passive non-declarant: undoes the cross and counts as acknowledgement
      // - declarant: server treats it as RESET_TURN (cancels the whole lock declaration)
      // Outside LOCK_PENDING, RESET_TURN clears the move.
      const moveType = this.turnState()?.phase === TurnPhase.LOCK_PENDING
        ? MoveType.UNDO_LAST_CROSS
        : MoveType.RESET_TURN;
      this.sendMove({ moveType });
      return;
    }

    const state = this.gameState();
    const turn  = this.turnState();
    if (!state || !turn?.currentRoll) return;

    const pid    = this.playerId();
    const layout = state.sheetLayouts[pid];
    const row    = layout?.rows.find(r => r.id === rowId);
    const cell   = row?.cells.find(c => c.id === cellId);

    // LONGO: both closing-eligible cells in a multi-required row show the self-close modal.
    // Second-to-last ("15"): crossing it is needed to qualify; the lock only fires when "16" is
    //   also crossed — YES sets pendingAutoLock so the lock fires automatically with "16".
    // Last ("16"): crossing it closes the row directly — the player should still confirm.
    // In LOCK_PENDING the player already committed (via YES on the second-to-last), so skip.
    if (row && cell?.closingEligible && (row.lock?.requiredCells?.length ?? 0) > 1
        && this.turnState()?.phase !== TurnPhase.LOCK_PENDING) {
      const requiredCells  = row.lock!.requiredCells;
      const secondToLastId = requiredCells[requiredCells.length - 2];
      const lastId         = requiredCells[requiredCells.length - 1];
      if (cell.id === secondToLastId || cell.id === lastId) {
        const req = this.buildCrossMoveRequest(row, cell, rowId, cellId);
        if (req) {
          const rowColor = (row.lock!.color ?? row.cells[0]?.color) as Color;
          this.rowClosureModal.showLockConfirm(
            rowColor,
            () => {
              // YES: cross the cell and set pendingAutoLock so the lock fires
              // (immediately for the last cell, or when "16" is crossed for "15").
              this.rowClosureModal.clearLockConfirm();
              this.pendingAutoLock.set({ rowId: row!.id, autoLock: true, cellId: cellId });
              this.sendMove(req);
            },
            () => {
              // NO: cross the cell but do not commit to closing the row.
              this.rowClosureModal.clearLockConfirm();
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
      this.sendMove({ moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId });
      return;
    }

    if (!cell || !row) return;

    const req = this.buildCrossMoveRequest(row, cell, rowId, cellId);
    if (req) {
      this.sendMove(req);
    } else if (this.clickableCellIds().has(cellId)) {
      // Cell is valid (e.g. Longo bonus cross) but its display value doesn't match
      // any dice combination directly — treat it as a white+white cross.
      this.sendMove({ moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId });
    }
  }

  private buildCrossMoveRequest(row: SheetRow, cell: SheetCell, rowId: string, cellId: string): MoveRequest | null {
    if (this.isInPassiveQueue()) {
      return { moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId };
    }
    const turn = this.turnState();
    if (!turn?.currentRoll) return null;
    const roll      = turn.currentRoll;
    const cellValue = parseInt(cell.displayValue);
    const rowColor  = row.cells[0]?.color as Color;
    const colorVal  = roll.coloredDice[rowColor] ?? null;
    const isWW    = cellValue === roll.white1 + roll.white2 && !turn.whiteWhiteUsed;
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
    this.sendMoveAs(pid, { moveType: MoveType.CROSS_LOCK, rowId });
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

    // Mirror LongoTurnRules.canCrossLock:
    // The current-turn pending crosses act as the undo buffer.
    const pending = this.pendingCellIds();

    const required = row.lock.requiredCells;
    const lastRequired = required[required.length - 1];

    // Last required cell in any crosses (permanent or pending) → eligible.
    if (permanent.has(lastRequired) || pending.has(lastRequired)) return true;

    // Second-to-last required cell enables locking only while it is still a
    // pending cross (crossed this turn).  Once permanent it no longer suffices.
    if (required.length > 1) {
      const secondLast = required[required.length - 2];
      return pending.has(secondLast);
    }
    return false;
  }

  private computePendingAutoLock(row: SheetRow, cell: SheetCell): { rowId: string; autoLock: boolean; cellId: string } | null {
    if (!this.wouldEnableLockDeclaration(row, cell.id)) return null;
    const requiredCells = row.lock!.requiredCells;
    const lastRequiredId = requiredCells[requiredCells.length - 1];
    const autoLock = cell.id === lastRequiredId;
    return { rowId: row.id, autoLock, cellId: cell.id };
  }

  private wouldEnableLockDeclaration(row: SheetRow, newCellId: string): boolean {
    const state = this.gameState();
    if (!state || !row.lock) return false;
    const pid       = this.playerId();
    const permanent = new Set(state.sheetProgress[pid]?.rowStates[row.id]?.crossedCells ?? []);
    const pending   = new Set([...this.pendingCellIds(), newCellId]);
    const all       = new Set([...permanent, ...pending]);
    return row.lock.requiredCells.every(id => all.has(id));
  }

  private canDeclareLockForRow(s: GameState, rowId: string): boolean {
    const pid      = this.playerId();
    const row      = s.sheetLayouts?.[pid]?.rows.find(r => r.id === rowId);
    if (!row?.lock) return false;
    if ((s.closedRows ?? {})[rowId]) return false;
    const rowState = s.sheetProgress?.[pid]?.rowStates?.[rowId];
    if (rowState?.lockCrossed) return false;
    const permanent = new Set(rowState?.crossedCells ?? []);
    const pending   = new Set(s.turnState?.pendingCrosses?.[pid] ?? []);
    const required  = row.lock.requiredCells;
    const last      = required[required.length - 1];
    // Only fire DECLARE_LOCK_INTENT when the last required cell is pending or permanent.
    // The second-to-last cell ("15" in Longo) does NOT trigger declaration — the server
    // requires ALL required cells to be crossed before it accepts a lock intent.
    return permanent.has(last) || pending.has(last);
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
    if (!this.canDeclareLockForRow(s, rowId)) {
      // Clear stale pendingAutoLock when the triggering cross has been undone.
      const pid = this.playerId();
      const permanent = new Set(s.sheetProgress?.[pid]?.rowStates?.[rowId]?.crossedCells ?? []);
      const pendingCrosses = new Set(s.turnState?.pendingCrosses?.[pid] ?? []);
      if (!permanent.has(cellId) && !pendingCrosses.has(cellId)) {
        this.pendingAutoLock.set(null);
        return;
      }
      // Triggering cell is still present but the last required cell hasn't been crossed yet
      // (Longo second-to-last scenario: player crossed "15", still needs "16").
      // Immediately declare lock intent so passive players see the modal. The declarant
      // must still cross the last required cell via color die while in LOCK_PENDING.
      const row = s.sheetLayouts?.[pid]?.rows.find(r => r.id === rowId);
      if (autoLock && (row?.lock?.requiredCells?.length ?? 0) > 1
          && s.turnState?.phase !== TurnPhase.LOCK_PENDING) {
        this.pendingAutoLock.set(null);
        this.sendMove({ moveType: MoveType.DECLARE_LOCK_INTENT, rowId });
      }
      return;
    }
    // While in LOCK_PENDING: the active declarant uses the confirm button to queue a
    // second row; an active acknowledger waits for the pending lock to resolve first.
    if (s.turnState?.phase === TurnPhase.LOCK_PENDING) return;
    this.pendingAutoLock.set(null);

    if (autoLock) {
      this.sendMove({ moveType: MoveType.DECLARE_LOCK_INTENT, rowId });
    } else {
      const row      = s.sheetLayouts?.[this.playerId()]?.rows.find(r => r.id === rowId);
      const rowColor = (row?.lock?.color ?? row?.cells[0]?.color) as Color;
      this.rowClosureModal.showLockConfirm(
        rowColor,
        () => {
          this.rowClosureModal.clearLockConfirm();
          this.sendMove({ moveType: MoveType.DECLARE_LOCK_INTENT, rowId });
        },
        () => this.rowClosureModal.clearLockConfirm()
      );
    }
  }

  private sendMoveAs(pid: string, req: MoveRequest) {
    if (this.moveSub && !this.moveSub.closed) {
      // A previous request is still in flight — cancel it but refresh state immediately
      // in case the server already processed it before the cancellation arrived.
      this.moveSub.unsubscribe();
      this.fetchState();
    }
    this.moveSub = this.movesService.makeMove(this.sessionId(), pid, req)
      .subscribe({
        next: () => this.fetchState(),
        error: e => {
          console.error('Move rejected:', e);
          // A rejection can mean a prior cancelled request already changed the state;
          // refresh so the UI reflects whatever the server actually did.
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
        next: () => this.fetchState(),
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

    if (this.rollingDice()) {
      const remaining = Math.max(0, this.ROLL_ANIM_MIN_MS - (Date.now() - this.rollStartTime));
      setTimeout(() => {
        // Re-check: a newer state may have arrived while the roll animation was playing.
        if ((s.version ?? 0) >= (this.gameState()?.version ?? -1)) {
          this.gameState.set(s);
        }
        this.rollingDice.set(false);
      }, remaining);
    } else {
      this.gameState.set(s);
      this.checkPendingAutoLock(s);
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
  myRowConnectors = computed((): Map<string, { above: number[], below: number[] }> => {
    const layout = this.layoutFor(this.playerId());
    const result = new Map<string, { above: number[], below: number[] }>();
    if (!layout) return result;
    for (let i = 0; i < layout.rows.length; i++) {
      const row = layout.rows[i];
      const aboveIds = new Set(layout.rows[i - 1]?.cells.map(c => c.id) ?? []);
      const belowIds = new Set(layout.rows[i + 1]?.cells.map(c => c.id) ?? []);
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
      result.set(row.id, { above, below });
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
    this.sendMove({ moveType: MoveType.PASS });
  }

  onChangeRowClosure() {
    if (this.hasPendingPassiveCross()) {
      // Player has a pending cross — undo it so they can reconsider.
      // Reset suppressModal so the modal re-shows after the undo.
      this.suppressModal.set(false);
      this.sendMove({ moveType: MoveType.UNDO_LAST_CROSS });
    } else {
      // No pending cross — dismiss the modal so the player can click a cell on the board.
      // The modal re-appears automatically once they make a cross (pendingCellIds > 0).
      this.suppressModal.set(true);
      this.rowClosureModal.clear();
    }
  }

  protected readonly DiceComponent = DiceComponent;
}
