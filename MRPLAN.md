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

---

## MR 12 — SheetLayout in game state

Packages: `web/`, OpenAPI spec

Critical prerequisite for the frontend — without the layout the client cannot render the board.

- Add `SheetCell`, `SheetRow`, `LockConfig`, `SheetLayout` DTO schemas to OpenAPI spec
- Extend `GET /gamestates/{sessionId}` response to include `sheetLayouts` (map of playerId → layout)
- Fix `turnState` as optional (not required) in the `GameState` schema — it is `null` in offline mode
- Update `GameStateMapper` to include the layout
- Tests

---

## MR 13 — Valid actions + scores endpoints

Packages: `web/`, OpenAPI spec

- `GET /gamestates/{sessionId}/{playerId}/valid-actions` → returns the list of currently valid moves for that player (move type + cell/row targets)
- `GET /games/{sessionId}/scores` → returns per-player `ScoreCard`; only available after game over
- Tests

---

## MR 14 — Complete online move types

Packages: `web/`, OpenAPI spec

Fills the remaining gap in the online lock flow and turn management.

- Add `DECLARE_LOCK_INTENT`, `RESET_TURN`, `GIVE_UP`, `UNDO_LAST_CROSS` to `MoveType`
- Route each to the corresponding `GameAction` in `MovesApiDelegateImpl`
- Tests

---

## MR 15 — Frontend: API service layer

Package: `client/`

- Configure OpenAPI Generator for TypeScript/Angular; generate services + models
- HTTP client setup, CORS proxy config, environment base URL

---

## MR 16 — Frontend: Lobby

Package: `client/`

- List games page, create game form (options from `/game-options`), join game (add player), routing between lobby and board

---

## MR 17 — Frontend: Board rendering (static)

Package: `client/`

- Row and Cell components driven by `SheetLayout` from game state
- Crossed / closed / lock visual states; no interaction yet
- Works for any row length (standard 11 cells, longo 15 cells)

---

## MR 18 — Frontend: Styling

Package: `client/`

All visual polish in one place; components from MR 17 already exist and carry the right CSS classes/structure.

- Color palette, typography, global reset (`styles.css`)
- Row colors (red/yellow/green/blue), cell shape, crossed-cell mark, closing-eligible highlight
- Lock cell appearance with lock icon SVG, closed-row overlay
- Dice display, punishment track, turn indicator
- Responsive layout for 2–6 player scoreboards
- No interaction logic in this MR — pure HTML/CSS

---

## MR 19 — Frontend: Online play — roll & cross


Package: `client/`

- Active player: roll button → dice display → tap cell to cross
- Passive player: tap matching white+white cell or pass
- Poll or version-check `/gamestates` for reactive updates

---

## MR 20 — Frontend: Online play — lock flow

Package: `client/`

- Declare lock intent, lock-pending acknowledge screen, cross-lock or undo

---

## MR 21 — Frontend: Game over + scores

Package: `client/`

- Score breakdown screen, per-color points, punishment deductions, winner highlight

---

## MR 22 — Frontend: Offline mode

Package: `client/`

- No turn indicator; any player can cross any reachable cell; take-punishment button; lock button per row

---

## MR 23 — Frontend: Longo bonus number display

Package: `client/`

Board rows need no special treatment — they render longer via the existing cell component.
The only Longo-specific UI is surfacing each player's personal bonus numbers.

- Show each player's 2 bonus numbers as a chip/badge on their sheet (sourced from `variantData`)
- Highlight the matching bonus number when `white1 + white2` equals one of them (derived from `turnState.currentRoll`)

---

## MR 24 — Audio + attributions

Packages: `server/`, `client/`

Audio files are served as static resources by the Spring Boot server; the Angular client fetches and plays them by URL.

- Place audio files under `server/src/main/resources/static/audio/`
- Spring Boot serves them automatically at `/audio/<filename>`
- Angular `AudioService`: loads sounds by URL, exposes `play(event)`, mute toggle, volume control
- Wire `AudioService` calls to game events: cell crossed, lock closed, dice rolled, punishment taken, game over
- `ATTRIBUTIONS.md` at the repo root listing each audio file, its source, licence, and any modifications