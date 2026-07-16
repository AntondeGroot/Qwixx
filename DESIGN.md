# Qwixx Game Engine — Design Document

## Core Principles

- **Position-based progression, never value-based.** Cell values are display labels only. The engine always uses positional ordering. This makes random-order row variants work without special-casing.
- **Colors live on cells, not rows.** Rows are purely structural containers. Scoring is per color across all cells on the board.
- **The lock is always the closing mechanism.** Crossing the lock cell closes a row. The lock has pre-conditions (minimum crosses, required cells crossed). This is always a deliberate player action — never auto-triggered.
- **GameState is immutable.** Each `applyAction` returns a new snapshot. Enables replay, undo, and network sync.
- **Variants are composable.** Features (random order, extra row, connected cells, etc.) are settings that can be combined freely on top of a base variant.

---

## Layer 1: Value Objects ✅

### Color
```
enum Color { RED, YELLOW, GREEN, BLUE }
```
One color per die. Colors are also the primary scoring buckets.

### CellTag
```
CellTag (sealed interface)
  AutoCross(String target)         // rule-time: auto-crosses target when this cell is crossed
  ExtraBucket                      // score-time: cell also contributes +1 to the EXTRA scoring bucket
  BonusPoints(int amount)          // score-time: crossing awards flat bonus points
  DoubleCross                      // score-time: cell counts twice in its primary color bucket
```

`TurnRules` consumes rule-time tags (`AutoCross`). `ScoringEngine` consumes score-time tags. Both use pattern matching — tags are pure data with no embedded logic.

### Cell
```
Cell {
  String        id
  int           position          // ordinal index in the row (engine uses this, not displayValue)
  String        displayValue      // shown to the player ("2".."12", "2".."16", etc.)
  Color         color             // the die color that can target this cell; also its primary scoring bucket
  List<CellTag> tags              // zero or more behavioral/scoring modifiers
  boolean       isClosingEligible // can this cell satisfy the lock's requiredCells? (e.g. 12, or 15/16 in longo)
}
```

The frontend derives visual styling from tags (e.g. thick black border for `ExtraBucket`, shape icon for other markers).

**AutoCross** bypasses the progression constraint. Auto-crossing a target cell is a free gift, not a player choice, so the rightmost position check does not apply.

### LockCell
```
LockCell {
  String       id
  Color        color           // determines which die is removed when this lock is crossed
  int          minCrosses      // crosses needed before lock is available (5 standard, 6 longo)
  List<String> requiredCells   // at least one of these must be crossed before lock is available
                               // standard: [cell_12] or [cell_2]
                               // longo:    [cell_15, cell_16] or [cell_3, cell_2]
}
```

The lock gives the crossing player a bonus cross toward their color score for `lockCell.color`.

### Row
```
Row {
  int        index             // ordinal position top-to-bottom (0 = top row)
  List<Cell> cells   // ordered by position
  LockCell   lock    // nullable — for variants that define a row without a closing lock
}
```

All rows enforce progression: a player can only cross a cell whose position is strictly greater than their current rightmost crossed position. Cells may be skipped but never revisited.

**Exception — AutoCross**: when a `CrossCellAction` is applied, only the explicitly chosen cell must satisfy the progression check. Any cells auto-crossed via an `AutoCross` tag skip the progression check entirely (they are a free gift, not a player choice).

### Die
```
Die {
  Color  color   // WHITE, RED, YELLOW, GREEN, BLUE
  int    faces   // 6 for standard, 8 for longo
}
```

### RollResult
```
RollResult {
  int             white1
  int             white2
  Map<Color, int> coloredDice   // only colors whose die is still in play
}
```

Valid combinations for crossing a cell:
- `white1 + white2` → any cell whose displayValue matches, any color (active + passive players)
- `white1 + colored[X]` → cells with X in their colors list, value matches (active player only)
- `white2 + colored[X]` → cells with X in their colors list, value matches (active player only)

---

## Layer 2: State

### CardMode
```
enum CardMode { SAME_CARDS, DIFFERENT_CARDS }
```
- `SAME_CARDS`: all players have identical rows (same cell IDs, same layout).
- `DIFFERENT_CARDS`: each player has their own generated rows (e.g. different random orderings). Cell IDs are globally unique per player.

### ActiveTurnState
Tracks what the active player has done this turn (one instance per turn, shared across all rows).

```
ActiveTurnState {
  boolean whiteWhiteUsed
  boolean colorDieUsed
}
```

| State | whiteWhiteUsed | colorDieUsed | Can cross white+white | Can cross white+color | Can confirm (end turn) | Can reset | Can give up (punishment) |
|---|---|---|---|---|---|---|---|
| Nothing done yet | false | false | yes | yes | no | yes | yes |
| Used white+white only | true | false | no | yes | yes | yes | yes |
| Used white+color only | false | true | no | no | yes | yes | yes |
| Used both | true | true | no | no | yes | yes | yes |

Key rules:
- Once the color die has been used, the white+white option is permanently closed for that turn. White+white must always come before white+color.
- Reset is always available. It reverts the acting player's crosses back to their `playerMoveStartState` snapshot.
- Give up (forfeit) is always available. Any crosses made this turn are discarded. The player takes a punishment cross.

### RowState
```
RowState {
  Set<String> crossedCells
  boolean     lockCrossed
}
```

`rightmostCrossedPosition` is derived by taking the max `position` across all crossed cells (empty set → -1). Not stored to avoid a second source of truth.

### SheetLayout
A player's row layout. Set once at game creation, never mutated.

```
SheetLayout {
  List<Row> rows   // identical across players in SAME_CARDS mode; unique per player in DIFFERENT_CARDS mode
}
```

### SheetProgress
A player's crossing progress. Changes every turn.

```
SheetProgress {
  Map<Integer, RowState> rowStates  // key is row index (0-based, top to bottom)
  int                  punishments
}
```

### TurnPhase
```
enum TurnPhase { ROLL, ACTIVE_MOVE, PASSIVE_MOVE, LOCK_PENDING, EVALUATE }
```

`LOCK_PENDING` is entered when the active player declares intent to close a row. All players are notified and may undo their last cross before the lock is confirmed. Once all players have either acted or passed, the active player confirms with `CrossLockAction`.

### BoardState
The dynamic state of the board — what has been established across all turns.

```
BoardState {
  Map<UUID, SheetProgress> sheetProgress  // crossing progress per player
  List<Die>                activeDice     // shrinks as rows are locked (color die removed)
  Map<Integer, UUID>       closedRows     // row index → player who closed it
}
```

### TurnState
Everything scoped to the turn currently in progress. Rebuilt at the start of each turn.

```
TurnState {
  UUID                        activePlayerId         // next is players[(indexOf(activePlayerId) + 1) % players.size()]
  TurnPhase                   phase
  List<UUID>                  passivePlayerQueue     // derived from players minus activePlayerId at turn start;
                                                     // shrinks as each passive player finishes their move
  RollResult                  currentRoll            // null during ROLL phase
  ActiveTurnState             activeTurnState        // null outside ACTIVE_MOVE
  Integer                     pendingLockRowIndex    // null outside LOCK_PENDING; the row the active player intends to close
  Set<UUID>                   lockAcknowledged       // players who have either undone or passed during LOCK_PENDING
  Map<UUID, SheetProgress>    moveStartProgress     // snapshot of each player's progress taken when their
                                                     // move phase begins; ResetTurnAction restores only
                                                     // that player's SheetProgress entry
}
```

### GameState
Top-level envelope. `SheetLayout` lives here as it is static; `BoardState` holds only what changes.

```
GameState {
  CardMode                    cardMode
  List<UUID>.                 players        // ordered; defines turn order
  VariantData                 variantData    // opaque, variant-specific data
  Map<UUID, SheetLayout>.     sheetLayouts   // static layout per player
  BoardState                  boardState
  TurnState                   turnState
  boolean                     gameOver
  long                        version        // increments on every applyAction
}
```

Clients poll a lightweight endpoint that returns only the current `version`. If it differs from their last known value, they fetch the full state and re-render. Version increments on every `applyAction` so that any visible change — a cell cross, a phase transition, a turn advance, game over — is detectable.

**Game end conditions** (checked after each action):
1. Two or more rows are locked (`boardState.closedRows.size() >= 2`).
2. Any player has 4 punishment crosses.

---

## Layer 3: Actions

```
GameAction (interface)
  UUID playerId()

CrossCellAction implements GameAction
  UUID.           playerId
  int             rowIndex
  String          cellId
  DiceCombination combination   // WHITE_WHITE | WHITE_COLOR

DeclareLockIntentAction implements GameAction  // active player announces intent to close a row;
  UUID      playerId                           // transitions phase to LOCK_PENDING
  int       rowIndex

CrossLockAction implements GameAction          // active player confirms the lock after all players
  UUID  playerId                           // have acknowledged; only valid in LOCK_PENDING phase
  int       rowIndex

UndoLastCrossAction implements GameAction      // any player removes their single most recent cross
  UUID  playerId                           // during LOCK_PENDING; does not affect other crosses

GiveUpAction implements GameAction     // active player forfeits the turn and takes a punishment cross;
  UUID  playerId                   // any crosses made this turn are discarded

ResetTurnAction implements GameAction  // reverts the acting player's crosses back to their playerMoveStartState snapshot; always available
  UUID  playerId

EndTurnAction implements GameAction    // player signals they are done
  UUID  playerId

TakePunishmentAction implements GameAction  // offline mode only: player manually records a punishment cross
  UUID  playerId
```

**Active player** buttons: "Confirm" (end turn), "Give Up" (forfeit + punishment), "Reset" (undo this turn's crosses).
**Passive players** buttons: "Done", "Reset". Passive players cannot give up — they are never punished for passing.

`TakePunishmentAction` is only valid in `OFFLINE` mode. It increments the player's punishment count by one and immediately checks game-end conditions — a player reaching maximum punishments ends the game.

---

## Layer 4: Rules & Scoring

### TurnRules
```
TurnRules (interface)
  List<GameAction> getValidActions(GameState state, UUID playerId)
  GameState        apply(GameState state, GameAction action)
  boolean          isGameOver(GameState state)
```

`getValidActions` is aware of whether the player is active or passive (derivable from `state.activePlayerId`).

When a `CrossLockAction` is applied:
1. Mark the row as closed in `closedRows`.
2. Add the lock bonus cross to the closing player's color score for `lockCell.color`.
3. Remove the die of `lockCell.color` from `activeDice`.
4. Check game-end conditions.

### TurnRules implementations
```
StandardTurnRules implements TurnRules
LongoTurnRules    implements TurnRules   // adds bonus number logic via LongoVariantData
OfflineTurnRules  implements TurnRules   // wraps a base TurnRules; skips turn/dice validation entirely
```

`OfflineTurnRules` delegates row-building and scoring to the underlying variant's factory but replaces all turn management:
- `getValidActions` returns a `CrossCellAction` for every cell that passes the progression check (position strictly greater than rightmost crossed), for every player, regardless of phase or dice. Also returns `CrossLockAction` for any row that meets lock pre-conditions, and `TakePunishmentAction` for any player.
- `apply` accepts those same actions without a phase check. `TurnState` is kept as `null` throughout — `apply` never reads or writes it. `AutoCross` tags are still honored: when a cell with an `AutoCross` tag is crossed, all target cells are also crossed automatically (bypassing the progression check, exactly as in online mode).
- `isGameOver` is identical to online mode: two or more rows closed, or any player at maximum punishments.

Longo bonus number logic: each player is assigned two personal bonus numbers at game start — each drawn independently from {5, 6, 7, 11, 12, 13}; the pair {7, 11} (in either order) is forbidden and re-drawn. When `white1 + white2` equals one of a player's bonus numbers, an additional `CrossCellAction(WHITE_WHITE)` becomes available: the player may cross the leftmost uncrossed cell in the row with the fewest crosses. Using the bonus consumes the white+white slot; white+color is still available afterward.

### ScoringEngine
```
ScoringEngine (interface)
  ScoreCard calculate(SheetLayout layout, SheetProgress progress)

ScoreCard {
  Map<Color, Integer> crossesPerColor   // count of crossed cells per color bucket (feeds triangular formula)
  Map<Color, Integer> pointsPerColor    // triangular: n*(n+1)/2 per color bucket
  int                 bonusPoints       // flat points from BonusPoints tags; added directly, not triangular
  int                 punishmentPoints  // -5 per punishment cross
  int                 total()           // sum of pointsPerColor values + bonusPoints + punishmentPoints
}
```

Scoring steps:
1. For each crossed cell, add 1 to the bucket for `cell.color`.
2. For each crossed cell, pattern-match its tags:
   - `ExtraBucket` → add 1 to the EXTRA bucket
   - `BonusPoints(n)` → add n flat points directly to the total
   - `DoubleCross` → add 1 more to `cell.color` bucket
3. For each row the player locked, add 1 to the bucket for `lockCell.color` (lock bonus cross).
4. Apply triangular formula `n*(n+1)/2` per color bucket (RED, YELLOW, GREEN, BLUE, EXTRA); store in `pointsPerColor`.
5. Add all accumulated `bonusPoints` flat (not triangular).
6. Subtract 5 per punishment cross.

---

## Layer 5: Settings, Factories & Registry

### GameMode
```
enum GameMode { ONLINE, OFFLINE }
```
- `ONLINE`: the server manages turn order, validates dice combinations, and enforces progression. All rules from `TurnRules` apply.
- `OFFLINE`: players use real physical dice. The server does not track whose turn it is, does not validate dice combinations, and does not enforce progression (skipping a cell is still allowed, but crossing to the left of the rightmost crossed position is not — the board must remain consistent for scoring). The server only tracks game-end conditions.

### GameSettings
Captures all choices for a game. Features can be combined freely on top of a base variant.

```
GameSettings {
  BaseVariant base            // STANDARD | LONGO
  boolean     randomOrder     // shuffle displayValues per row
  boolean     connectedCells  // some cells carry an AutoCross tag
  boolean     extraRow        // add ExtraBucket-tagged cells forming a sinus wave across the 4 rows
  boolean     mixedColors     // cells within rows have varying colors (dice logic TBD)
  CardMode    cardMode        // SAME_CARDS | DIFFERENT_CARDS
  GameMode    gameMode        // ONLINE | OFFLINE
}
```

Example: longo + random order + connected cells + extra row:
```
GameSettings { base: LONGO, randomOrder: true, connectedCells: true, extraRow: true, cardMode: SAME_CARDS, gameMode: ONLINE }
```

Example: standard offline game (real dice, shared scorecard):
```
GameSettings { base: STANDARD, cardMode: SAME_CARDS, gameMode: OFFLINE }
```

### VariantData
```
VariantData (interface)           // opaque; each variant defines its own subtype

LongoVariantData implements VariantData
  Map<UUID, List<Integer>> bonusNumbersPerPlayer  // 2 bonus numbers per player; assigned at game start
```

### GameStyleFactory
A single configurable factory driven by `GameSettings`. Internally it composes row-building steps as a pipeline: base rows → apply random order → apply extra row cells → apply connected cells → apply mixed colors.

```
GameStyleFactory (interface)
  GameSettings             settings()
  Map<UUID, List<Row>> buildRows(List<UUID> players)
  List<Die>                buildDice()
  TurnRules                buildTurnRules()
  ScoringEngine            buildScoringEngine()
  VariantData              buildVariantData(List<UUID> playerIds)

ConfigurableGameStyleFactory implements GameStyleFactory   // default implementation
```

The interface remains for extensibility — a fully custom factory can still be provided if needed.

### GameRegistry
Stores named presets and manages all active sessions.

```
GameRegistry {
  Map<String, GameSettings>   presets
  Map<SessionId, GameSession> sessions

  registerPreset(String name, GameSettings settings)
  GameSession createSession(GameSettings settings, List<Player> players)
  GameSession createSessionFromPreset(String presetName, List<Player> players)
  GameSession getSession(SessionId id)
}
```

Built-in presets registered on startup:
- `"standard"` → `{ base: STANDARD, all features false, SAME_CARDS }`
- `"longo"` → `{ base: LONGO, all features false, SAME_CARDS }`
- `"extra-row"` → `{ base: STANDARD, extraRow: true, SAME_CARDS }`
- `"random"` → `{ base: STANDARD, randomOrder: true, DIFFERENT_CARDS }`

### GameSession
```
GameSession {
  SessionId    id
  GameSettings settings
  List<Player> players
  GameState    currentState

  GameState applyAction(GameAction action)
  startNewGame(GameSettings settings)   // null = reuse current settings
  ScoreCard getScore(UUID playerId)
}
```

---

## Dependency Direction

```
GameRegistry
  └── GameStyleFactory  ──► TurnRules
                        ──► ScoringEngine
                        ──► VariantData
  └── GameSession
        └── GameState
              └── SheetLayout
                    └── Row
                          └── Cell
                                └── CellTag
                          └── LockCell
              └── BoardState
                    └── SheetProgress
                          └── RowState
              └── TurnState
                    └── ActiveTurnState
                    └── RollResult
                    └── SheetProgress
```

Each layer depends only on layers below it. `Cell` and `Row` know nothing about rules or sessions. `GameRegistry` owns `GameSession`s — not the other way around.

---

## Gameplay Interaction

### Crossing a Cell (single click)

The server resolves valid actions via `getValidActions` before the player moves. For each crossable cell it determines the correct `DiceCombination` using the following priority:

1. If `colorDieUsed` is already true → cell is not a valid action.
2. Try **white+white** first (if `whiteWhiteUsed` is false and `white1 + white2` matches the cell's `displayValue` and the cell passes the progression check) → `WHITE_WHITE`.
3. Else try **white+color** (if a colored die matching the cell's color is in play and either `white1 + colorDie` or `white2 + colorDie` matches, and the cell passes the progression check) → `WHITE_COLOR`.
4. Neither matched → cell is not included in valid actions.

The frontend fires a `CrossCellAction` on click. The player never needs to choose a combination — and all validation stays on the server.

### Closing a Row (two-step lock)

1. Active player fires `DeclareLockIntentAction` → phase transitions to `LOCK_PENDING`, `pendingLockRowId` is set, all players are notified.
2. Each other player may fire `UndoLastCrossAction` (removes only their most recent cross this turn) or `EndTurnAction` to pass. Either action adds them to `lockAcknowledged`.
3. Once `lockAcknowledged` contains all non-active players, `CrossLockAction` becomes available to the active player.
4. Active player fires `CrossLockAction` → row is closed, die is removed, lock bonus applied, phase advances to `EVALUATE`.

`UndoLastCrossAction` is intentionally limited to one cross. It exists only to let players react to new information (the impending row closure), not to freely revise their turn.

During `LOCK_PENDING`, any other eligible player may fire `CrossLockAction` directly as their acknowledgment — this also locks the row for them but does **not** re-trigger a new `LOCK_PENDING` cycle. `DeclareLockIntentAction` is only valid when the phase is not already `LOCK_PENDING`.

---

## Variant / Feature Notes

### Base: Standard
- 4 rows: RED (2→12), YELLOW (2→12), GREEN (12→2), BLUE (12→2)
- All cells have a single color
- `minCrossesToLock: 5`, `requiredCells: [lastCell]`

### Base: Longo
- 4 rows, values 2→16 and 16→2
- `minCrossesToLock: 6`
- Ascending rows: `requiredCells: [cell_15, cell_16]`; descending: `requiredCells: [cell_3, cell_2]`
- Lock is always manually triggered — player decides when to close
- Other players can still cross cells in a row that meets lock conditions until someone crosses the lock
- Bonus numbers stored in `LongoVariantData`
- Dice have 8 faces

### Feature: Extra Row
- One cell per column (excluding the lock) is tagged `ExtraBucket`, forming a bouncing pattern across the 4 rows
- The bounce follows the period-6 sequence `{0,1,2,3,2,1}` (0-indexed row), repeating indefinitely
- A random start offset (0–5) is drawn independently per player at game start — the pattern is always probabilistic, even in `SAME_CARDS` card mode
- No Row object for the EXTRA scoring bucket — it exists only in `ScoreCard`
- Tagged cells score in both their primary `cell.color` bucket and the EXTRA bucket
- Frontend renders a thick black border on cells with the `ExtraBucket` tag

### Feature: Random Order
- `displayValue` is shuffled per row; engine uses `position` throughout
- `SAME_CARDS`: all players get the same shuffle → identical cards
- `DIFFERENT_CARDS`: each player gets an independent shuffle → different cards

### Feature: Connected Cells
- Some cells carry an `AutoCross(targets)` tag
- When a cell is crossed, all cells in `targets` are also crossed automatically
- Auto-crossed cells bypass the progression check
- Which cells are linked and how they are chosen: TBD

### Feature: Mixed Color Rows
- Cells within a row have varying colors
- Dice-to-cell mapping: white + colorDie can cross any cell on the board with that color in its `colors` list, regardless of which row it sits in
- Full logic: TBD

---

## Mode: Offline

Selected via `GameSettings.gameMode = OFFLINE`. Compatible with any base variant and any feature flag.

**What the server does not do in offline mode:**
- Track whose turn it is (no active/passive distinction, no `TurnPhase`).
- Validate dice combinations (no `RollResult`, no `DiceCombination`).
- Enforce turn order or phase transitions.

**What the server still does:**
- Enforce left-to-right progression per player per row (a cell can only be crossed if its position is strictly greater than that player's current rightmost crossed position). This keeps the scorecard consistent.
- Honor `AutoCross` tags: crossing a cell that carries an `AutoCross` tag also crosses its targets automatically, bypassing the progression check (same behavior as online mode).
- Enforce lock pre-conditions (`minCrosses`, `requiredCells`) before allowing `CrossLockAction`.
- Track closed rows in `boardState.closedRows`.
- Track punishment crosses via `TakePunishmentAction`.
- Detect game end: two or more rows closed, or any player at maximum punishments (4 standard).
- Maintain `version` for polling.

**`TurnState`** is `null` for the lifetime of an offline session. `GameState.turnState` is nullable; code that reads it must guard on `gameMode`.

**`GameStyleFactory`** behaviour in offline mode: `buildTurnRules()` returns `new OfflineTurnRules(baseRules)` where `baseRules` is the variant's own `TurnRules`. Row building, dice, and scoring are unchanged.

**Game-end conditions** (unchanged from online):
1. `boardState.closedRows.size() >= 2`
2. Any player has 4 punishment crosses.