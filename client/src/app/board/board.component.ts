import { AfterViewInit, Component, computed, effect, ElementRef, HostListener, inject, OnDestroy, OnInit, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { interval, Subscription, switchMap } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { MovesService } from '../../generated/api/moves.service';
import { CellTag } from '../../generated/model/cellTag';
import { Color } from '../../generated/model/color';
import { GameState } from '../../generated/model/gameState';
import { MoveRequest } from '../../generated/model/moveRequest';
import { MoveType } from '../../generated/model/moveType';
import { RowState } from '../../generated/model/rowState';
import { SheetLayout } from '../../generated/model/sheetLayout';
import { SheetProgress } from '../../generated/model/sheetProgress';
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
  private route              = inject(ActivatedRoute);
  private router             = inject(Router);
  private gameStatesService  = inject(GamestatesService);
  private movesService       = inject(MovesService);
  private host               = inject(ElementRef<HTMLElement>);
  private rowClosureModal    = inject(RowClosureModalService);

  sessionId   = signal('');
  playerId    = signal('');
  gameState   = signal<GameState | null>(null);
  error       = signal<string | null>(null);
  rollingDice = signal(false);
  // True while the player dismissed the lock-intent modal to pick a new cell.
  // Suppresses the modal until they cross something (pendingCellIds becomes non-empty).
  private suppressModal = signal(false);

  private pollSub?: Subscription;
  private moveSub?: Subscription;
  private stateSub?: Subscription;

  // Sync modal state to the service so the modal renders at the root level,
  // outside the board's CSS transform (which would break position:fixed on mobile).
  private _modalSync = effect(() => {
    const requests = this.isInPassiveQueue()
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
        () => this.onChangeRowClosure()
      );
    }
  });
  private rollStartTime = 0;

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
    // Subtract the host's top padding (16px) from the available height so the
    // board never scrolls past the bottom clip boundary.
    const scale = h > 0
      ? (window.innerWidth - 16) / h
      : (window.innerWidth - 16) / this.MOBILE_DESIGN_H;
    el.style.setProperty('--mobile-scale', Math.min(scale, 1).toFixed(4));
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
        this.collectBonusCells(state, layout, progress, closedRows, roll.white1 + roll.white2, result);
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

  private collectCells(
    layout:       SheetLayout,
    progress:     SheetProgress | undefined,
    closedRows:   Record<string, string>,
    targetValue:  number,
    restrictRow:  string | null,
    result:       Set<string>
  ) {
    for (const row of layout.rows) {
      if (restrictRow && row.id !== restrictRow) continue;
      if (closedRows[row.id]) continue;
      const crossed  = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
      const lastPos  = Math.max(-1, ...row.cells.filter(c => crossed.has(c.id)).map(c => c.position));
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
  }

  // When the white sum matches one of the active player's Longo bonus numbers,
  // the leftmost available cell in each row with the fewest crosses is also a valid
  // cross target — mirroring the server's addBonusCellAction logic.
  private collectBonusCells(
    state:      import('../../generated/model/gameState').GameState,
    layout:     SheetLayout,
    progress:   SheetProgress | undefined,
    closedRows: Record<string, string>,
    whiteSum:   number,
    result:     Set<string>
  ) {
    const bonusNums: number[] = state.bonusNumbers?.[this.playerId()] ?? [];
    if (!bonusNums.includes(whiteSum)) return;

    let fewest = Infinity;
    for (const row of layout.rows) {
      if (closedRows[row.id]) continue;
      const count = progress?.rowStates[row.id]?.crossedCells?.length ?? 0;
      if (count < fewest) fewest = count;
    }
    if (!isFinite(fewest)) return;

    for (const row of layout.rows) {
      if (closedRows[row.id]) continue;
      const crossed  = new Set(progress?.rowStates[row.id]?.crossedCells ?? []);
      if (crossed.size !== fewest) continue;
      const lastPos  = crossed.size > 0
        ? Math.max(...row.cells.filter(c => crossed.has(c.id)).map(c => c.position))
        : -1;
      const leftmost = row.cells
        .filter(c => !crossed.has(c.id) && c.position > lastPos)
        .sort((a, b) => a.position - b.position)[0];
      if (leftmost) result.add(leftmost.id);
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
      // In LOCK_PENDING the correct server action is UNDO_LAST_CROSS: it undoes
      // the cross and simultaneously adds the player to lockAcknowledged.
      // RESET_TURN would clear rowClosureRequests (modal vanishes) without
      // acknowledging, leaving the passive player permanently stuck.
      const moveType = this.turnState()?.phase === TurnPhase.LOCK_PENDING
        ? MoveType.UNDO_LAST_CROSS
        : MoveType.RESET_TURN;
      this.sendMove({ moveType });
      return;
    }

    const state = this.gameState();
    const turn  = this.turnState();
    if (!state || !turn?.currentRoll) return;

    // Passive players may only use white+white — skip move-type computation entirely.
    if (this.isInPassiveQueue()) {
      this.sendMove({ moveType: MoveType.CROSS_WHITE_WHITE, rowId, cellId });
      return;
    }

    const pid    = this.playerId();
    const layout = state.sheetLayouts[pid];
    const row    = layout?.rows.find(r => r.id === rowId);
    const cell   = row?.cells.find(c => c.id === cellId);
    if (!cell || !row) return;

    const roll      = turn.currentRoll;
    const cellValue = parseInt(cell.displayValue);
    const rowColor  = row.cells[0]?.color as Color;
    const colorVal  = roll.coloredDice[rowColor] ?? null;

    const isWW    = cellValue === roll.white1 + roll.white2 && !turn.whiteWhiteUsed;
    const isColor = colorVal != null &&
      (cellValue === roll.white1 + colorVal || cellValue === roll.white2 + colorVal) &&
      !turn.colorDieUsed;

    const moveType = (isColor && !isWW) ? MoveType.CROSS_COLOR_DIE : MoveType.CROSS_WHITE_WHITE;
    this.sendMove({ moveType, rowId, cellId });
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
    const crossed = new Set(rowState?.crossedCells ?? []);
    return row.lock.requiredCells.every(id => crossed.has(id));
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
    }
  }

  private fetchState() {
    // Cancel any in-flight state fetch so that only the most recent response wins.
    this.stateSub?.unsubscribe();
    this.stateSub = this.gameStatesService.getGameState(this.sessionId()).subscribe({
      next: (s: GameState) => this.applyState(s),
      error: () => this.error.set('Could not load game state.')
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
