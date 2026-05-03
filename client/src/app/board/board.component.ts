import { AfterViewInit, Component, computed, effect, ElementRef, HostListener, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { interval, Subscription, switchMap } from 'rxjs';
import { TranslateModule } from '@ngx-translate/core';
import { GamestatesService } from '../../generated/api/gamestates.service';
import { MovesService } from '../../generated/api/moves.service';
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
import { RowClosureModalComponent, RowClosureRequest } from '../row-closure-modal/row-closure-modal.component';

@Component({
  selector: 'app-board',
  imports: [RouterLink, RowComponent, DiceComponent, PlayerListComponent, TranslateModule, RowClosureModalComponent],
  templateUrl: './board.component.html',
  styleUrl: './board.component.css'
})
export class BoardComponent implements OnInit, AfterViewInit, OnDestroy {
  private route             = inject(ActivatedRoute);
  private router            = inject(Router);
  private gameStatesService = inject(GamestatesService);
  private movesService      = inject(MovesService);
  private host              = inject(ElementRef<HTMLElement>);

  sessionId   = signal('');
  playerId    = signal('');
  gameState   = signal<GameState | null>(null);
  error       = signal<string | null>(null);
  rollingDice = signal(false);

  // Only show the lock-intent modal to players who are in the passive queue
  // (i.e. players who must decide, not the active player who declared intent)
  rowClosureRequests = computed(() => {
    if (!this.isInPassiveQueue()) return [];
    return this.gameState()?.rowClosureRequests ?? [];
  });

  private pollSub?: Subscription;
  private moveSub?: Subscription;
  private rollStartTime = 0;

  // Fixed landscape design height (CSS px, derived from known element sizes).
  // Scale = phone-portrait-width / MOBILE_DESIGN_H.  Computed once on init and
  // on resize — never per game-state change, so the board never jumps mid-game.
  private readonly MOBILE_DESIGN_H = 541;
  private readonly ROLL_ANIM_MIN_MS = 2800;

  readonly emptySet  = new Set<string>();
  readonly TurnPhase = TurnPhase;

  private gameOverNavigated = false;
  private _gameOverEffect = effect(() => {
    if (this.gameState()?.gameOver && !this.gameOverNavigated) {
      this.gameOverNavigated = true;
      setTimeout(() => this.router.navigate(['/score', this.sessionId()]), 1500);
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

  ngAfterViewInit() {
    this.applyMobileScale();
  }

  @HostListener('window:resize')
  applyMobileScale() {
    const el = this.host.nativeElement as HTMLElement;
    if (window.innerHeight > window.innerWidth) {
      el.style.setProperty('--mobile-scale', (window.innerWidth / this.MOBILE_DESIGN_H).toFixed(4));
    } else {
      el.style.removeProperty('--mobile-scale');
    }
  }

  ngOnDestroy() {
    this.pollSub?.unsubscribe();
    this.moveSub?.unsubscribe();
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
      && (phase === TurnPhase.PASSIVE_MOVE || phase === TurnPhase.ACTIVE_MOVE);
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
    } else if ((turn.phase === TurnPhase.PASSIVE_MOVE || turn.phase === TurnPhase.ACTIVE_MOVE)
               && this.isInPassiveQueue()) {
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
        result.add(cell.id);
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
      this.sendMove({ moveType: MoveType.RESET_TURN });
      return;
    }

    const state = this.gameState();
    const pid   = this.playerId();
    const turn  = this.turnState();
    if (!state || !turn?.currentRoll) return;

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
    return crossed.size >= row.lock.minCrosses &&
           row.lock.requiredCells.every(id => crossed.has(id));
  }

  private sendMoveAs(pid: string, req: MoveRequest) {
    this.moveSub?.unsubscribe();
    this.moveSub = this.movesService.makeMove(this.sessionId(), pid, req)
      .subscribe({
        next: () => this.fetchState(),
        error: e => console.error('Move rejected:', e)
      });
  }

  private sendMove(req: MoveRequest) {
    this.moveSub?.unsubscribe();
    this.moveSub = this.movesService.makeMove(this.sessionId(), this.playerId(), req)
      .subscribe({
        next: () => this.fetchState(),
        error: e => {
          this.rollingDice.set(false);
          console.error('Move rejected:', e);
        }
      });
  }

  private applyState(s: GameState) {
    if (this.rollingDice()) {
      const remaining = Math.max(0, this.ROLL_ANIM_MIN_MS - (Date.now() - this.rollStartTime));
      setTimeout(() => {
        this.gameState.set(s);
        this.rollingDice.set(false);
      }, remaining);
    } else {
      this.gameState.set(s);
    }
  }

  private fetchState() {
    this.gameStatesService.getGameState(this.sessionId()).subscribe({
      next: (s: GameState) => this.applyState(s),
      error: () => this.error.set('Could not load game state.')
    });
  }

  // ── View helpers ───────────────────────────────────────────────────────────

  layoutFor(pid: string): SheetLayout | null {
    return this.gameState()?.sheetLayouts?.[pid] ?? null;
  }

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
    // Modal will close once server clears rowClosureRequests from gameState
  }

  onChangeRowClosure() {
    this.sendMove({ moveType: MoveType.RESET_TURN });
  }

  protected readonly DiceComponent = DiceComponent;
}
