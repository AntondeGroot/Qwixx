# Qwixx — MR Plan

Based on what is already implemented (Layer 1 value objects, Layer 2 state objects, OpenAPI spec) this plan sequences the remaining work into focused, reviewable merge requests.

**Dependency order:** MR 1 → MR 2, 3 → MR 4 → MR 5 → MR 6 → MR 7. MRs 8–11 depend on MR 7 and can be done in parallel with each other.

---

## MR 1 — Actions

Package: `action/`

The sealed `GameAction` interface is the foundation everything else compiles against. Ship this first so downstream MRs can depend on concrete action types.

- `GameAction` sealed interface
- Records: `RollAction`, `CrossCellAction`, `DeclareLockIntentAction`, `CrossLockAction`, `UndoLastCrossAction`, `GiveUpAction`, `ResetTurnAction`, `EndTurnAction`, `TakePunishmentAction`
- `DiceCombination` enum (`WHITE_WHITE | WHITE_COLOR`)

---

## MR 2 — Scoring engine

Package: `rules/`

Pure function with no dependency on turn rules or session state — easy to test in isolation and can be developed in parallel with MR 3.

- `ScoringEngine` interface
- `ScoreCard` record (with derived `total()`)
- `StandardScoringEngine` — triangular formula `n*(n+1)/2`, tag pattern matching (`ExtraBucket`, `DoubleCross`, `BonusPoints`)
- Unit tests

---

## MR 3 — Game settings & factory skeleton

Package: `game/`

Defines configuration and builds the initial `GameState` for standard games. Features (random order, extra row, etc.) are added later as pipeline steps.

- `GameMode` enum (`ONLINE | OFFLINE`)
- `GameSettings` + builder
- `VariantData` interface + `LongoVariantData`
- `GameStyleFactory` interface
- `ConfigurableGameStyleFactory` — standard row builder only (no feature flags yet)
- `Player` record
- Unit tests for row building

---

## MR 4 — Standard turn rules

Package: `rules/`

The most complex piece of the engine. Deserves its own focused MR with thorough tests before anything builds on top of it.

- `TurnRules` interface
- `StandardTurnRules` — full phase machine (`ROLL → ACTIVE_MOVE → PASSIVE_MOVE → LOCK_PENDING → EVALUATE`), cell reachability, lock two-step, game-end conditions
- Unit tests for every phase transition and edge case

---

## MR 5 — Game session & registry

Package: `game/`

Wires turn rules and factory into a running session. Also exposes configurable options to the HTTP layer.

- `GameSession` (synchronized `applyAction`, `SessionStatus`, `start()`)
- `GameRegistry` (`ConcurrentHashMap`, static utility)
- `QwixxGameOptions` (`all()` + `apply()`) — `cardMode`, `randomOrder`, `extraRow`, `gameMode`
- Built-in presets: `"standard"`, `"longo"`, `"extra-row"`, `"random"`
- Integration tests

---

## MR 6 — Web layer: games, players & error handling

Package: `web/services/`

First half of the HTTP surface. Establishes the error-handling backbone used by all subsequent delegates.

- `GamesApiDelegateImpl` (`createNewGame`, `startGame`, `stopGame`, `getAllGames`)
- `PlayersApiDelegateImpl` (`addPlayerToGame`, `leaveGame`)
- Typed domain exceptions (`SessionNotFoundException`, `GameAlreadyStartedException`, `GameNotStartedException`, `IllegalMoveException`)
- `@RestControllerAdvice` mapping exceptions → HTTP status + `ErrorResponseDto`
- `@WebMvcTest` tests

---

## MR 7 — Web layer: moves & game state

Package: `web/services/`

Second half of the HTTP surface; includes the full DTO mapper.

- `MovesApiDelegateImpl` — `MoveRequest` → `GameAction` mapping, `getValidActions` guard, `applyAction`
- `GameStatesApiDelegateImpl` — version-based `304` shortcut
- Full DTO mapper (`GameState`, `TurnState`, `SheetProgress`, `RollResult`)
- `@WebMvcTest` tests

---

## MR 8 — Longo variant

Packages: `rules/`, `game/`

- `LongoTurnRules` — bonus number logic, stricter lock pre-conditions (`minCrosses: 6`, `requiredCells: [15,16]` / `[3,2]`)
- Longo row builder in `ConfigurableGameStyleFactory` (2→16 / 16→2, 8-faced dice)
- Register `"longo"` preset in `GameRegistry`
- Tests

---

## MR 9 — Feature: random order

Package: `game/`

Self-contained pipeline step; can be developed in parallel with MRs 8, 10, 11.

- Random order shuffle step in `ConfigurableGameStyleFactory`
- `PROBABILISTIC` card mode wiring (per-player independent shuffles)
- Tests

---

## MR 10 — Feature: extra row

Package: `game/`

- Sinus-wave `ExtraBucket` cell injection pipeline step in `ConfigurableGameStyleFactory`
- Tests

---

## MR 11 — Offline mode

Packages: `rules/`, `game/`, `web/`, OpenAPI spec

Touches several layers — keep the MR focused by implementing only what the design specifies (no turn tracking, no dice validation, game-end conditions unchanged).

- `OfflineTurnRules` wrapping base variant rules — progression check only, `AutoCross` tags honored, `TurnState` stays `null`
- `ConfigurableGameStyleFactory` returns `OfflineTurnRules` when `gameMode == OFFLINE`
- OpenAPI spec: add `TAKE_PUNISHMENT` to `MoveType`, add `gameMode` to `NewGameRequest` options
- DTO mapper: omit `TurnState` fields (`currentRoll`, `activePlayerId`, `phase`, `passivePlayerQueue`) when `gameMode == OFFLINE`
- `MovesApiDelegateImpl`: route `TAKE_PUNISHMENT` → `TakePunishmentAction`
- Tests