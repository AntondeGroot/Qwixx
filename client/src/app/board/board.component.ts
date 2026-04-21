import { Component, computed, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { interval, Subscription, switchMap } from 'rxjs';
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
import { RowComponent } from '../row/row.component';

@Component({
  selector: 'app-board',
  imports: [RouterLink, RowComponent, DiceComponent],
  templateUrl: './board.component.html',
  styleUrl: './board.component.css'
})
export class BoardComponent implements OnInit, OnDestroy {
  private route             = inject(ActivatedRoute);
  private gameStatesService = inject(GamestatesService);
  private movesService      = inject(MovesService);

  sessionId   = signal('');
  playerId    = signal('');
  gameState   = signal<GameState | null>(null);
  error       = signal<string | null>(null);
  rollingDice = signal(false);

  private pollSub?: Subscription;
  private moveSub?: Subscription;
  private rollStartTime = 0;
  private readonly ROLL_ANIM_MIN_MS = 2800;

  readonly emptySet  = new Set<string>();
  readonly TurnPhase = TurnPhase;

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
          this.applyState(s);
        }
      },
      error: () => {}
    });
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

  canPassActive = computed(() =>
    this.isMyTurn() && this.turnState()?.phase === TurnPhase.ACTIVE_MOVE
  );

  canPassPassive = computed(() =>
    this.isInPassiveQueue() && this.turnState()?.phase === TurnPhase.PASSIVE_MOVE
  );

  gameFaces = computed((): 6 | 8 => {
    const layout = this.gameState()?.sheetLayouts[this.playerId()];
    return (layout?.rows[0]?.cells.length ?? 11) > 11 ? 8 : 6;
  });

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
      if (!turn.whiteWhiteUsed) {
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
    } else if (turn.phase === TurnPhase.PASSIVE_MOVE && this.isInPassiveQueue()) {
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
    this.sendMove({ moveType: MoveType.TAKE_PUNISHMENT });
  }

  onCellClicked(rowId: string, cellId: string) {
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
}