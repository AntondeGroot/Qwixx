# Qwixx — Implementation

This document describes how the server is structured and how the pieces fit together. It assumes familiarity with `DESIGN.md` (the domain model) and `../qwixx_openapi.yml` (the HTTP API).

---

## Package layout

```
nl.adg.qwixx
├── data/          value objects  (Cell, Row, Die, RollResult, …)
├── state/         state snapshots  (GameState, BoardState, TurnState, …)
├── action/        GameAction sealed interface and all implementations
├── rules/         TurnRules, ScoringEngine, ScoreCard
├── game/          GameSettings, QwixxGameOptions, GameStyleFactory,
│                  GameSession, GameRegistry, Player
└── web/
    └── services/  delegate implementations (one per OpenAPI tag)
```

Each layer depends only on the layers below it. Nothing below `web` imports Spring.

---

## OpenAPI generator and the delegate pattern

The server uses the OpenAPI generator to produce Spring controller boilerplate from `../qwixx_openapi.yml`. The generator emits a controller class and a companion delegate interface per tag (e.g. `GamesApi` + `GamesApiDelegate`). You only implement the delegate — the generated controller forwards every request to it automatically.

```java
@Service
public class GamesApiDelegateImpl implements GamesApiDelegate {

  @Override
  public ResponseEntity<Object> createNewGame(NewGameRequest request) { … }

  @Override
  public ResponseEntity<Void> startGame(String sessionId) { … }
}
```

This keeps generated code separate from hand-written code and makes it easy to regenerate the spec without touching business logic.

---

## GameRegistry

`GameRegistry` is a static utility class. It holds a `ConcurrentHashMap<String, GameSession>` that maps session IDs to sessions. Being static means it is accessible from any delegate without Spring injection, which keeps the delegates simple.

```java
public class GameRegistry {
  private static final Map<String, GameSession> games = new ConcurrentHashMap<>();

  public static String createGame(String roomName, int maxPlayers, GameSettings settings) {
    String id = UUID.randomUUID().toString();
    games.put(id, new GameSession(id, roomName, maxPlayers, settings));
    return id;
  }

  public static GameSession getGame(String sessionId) {
    return games.get(sessionId);   // null if not found — callers check
  }

  public static List<GameInfo> getAllGames() { … }

  public static void removeGame(String sessionId) {
    games.remove(sessionId);
  }
}
```

`ConcurrentHashMap` handles concurrent reads from different players safely. Individual operations on a session (applying a move, starting the game) are synchronized inside `GameSession` itself.

---

## GameSession

`GameSession` owns everything that belongs to one running game: the session metadata (ID, room name, max players, settings), the current `GameState`, and the `TurnRules` instance chosen for this game's variant.

```java
public class GameSession {
  private final String       sessionId;
  private final String       roomName;
  private final int          maxPlayers;
  private final GameSettings settings;
  private final TurnRules    rules;
  private       List<Player> players   = new ArrayList<>();
  private       GameState    state;
  private       SessionStatus status   = SessionStatus.WAITING;
}
```

`SessionStatus` is `WAITING` (lobby), `IN_PROGRESS`, or `FINISHED`. This is separate from `GameState.gameOver` because the lobby phase has no `GameState` yet — state is created only when the game starts.

### Starting the game

When `start()` is called, the factory builds the initial `GameState` from the registered players and the `GameSettings`. The session transitions from `WAITING` to `IN_PROGRESS`. Any call to `addPlayer` after this point is rejected.

### Applying actions

`applyAction` is the single entry point for all game moves. It is `synchronized` on the session object to prevent two players from submitting moves simultaneously and corrupting the state:

```java
public synchronized GameState applyAction(GameAction action) {
  state = rules.apply(state, action);
  return state;
}
```

`TurnRules.apply` returns a new `GameState` (see `DESIGN.md` — state is immutable). The session simply replaces its reference. Reads (`currentState()`) are also synchronized so a polling client never sees a half-written state.

---

## Actions

`GameAction` is a sealed interface. This allows `TurnRules.apply` to use an exhaustive `switch` expression — the compiler enforces that every action type is handled, removing the need for a `default` branch or runtime `instanceof` checks.

```java
public sealed interface GameAction permits
    RollAction, CrossCellAction, DeclareLockIntentAction, CrossLockAction,
    UndoLastCrossAction, GiveUpAction, ResetTurnAction, EndTurnAction,
    TakePunishmentAction {
  UUID playerId();
}
```

Each implementation is a record — data only, no logic. The `DiceCombination` is resolved by the server and included in `CrossCellAction`; the player just clicks a cell and the server works out whether the move uses `WHITE_WHITE` or `WHITE_COLOR` based on `ActiveTurnState` and the current roll.

---

## TurnRules

```java
public interface TurnRules {
  List<GameAction> getValidActions(GameState state, UUID playerId);
  GameState        apply(GameState state, GameAction action);
  boolean          isGameOver(GameState state);
}
```

`getValidActions` is used by the moves delegate to validate the incoming move before applying it. It is also the data source for any "highlight valid cells" feature on the client.

### How getValidActions works

Whether a player is active or passive is derived from `state.turnState().activePlayerId()`. The legal set differs:

**Active player actions by phase:**
- `ROLL` — only `RollAction`.
- `ACTIVE_MOVE` — a `CrossCellAction` for every reachable cell (see below), always `GiveUpAction` and `ResetTurnAction`, and `EndTurnAction` once `whiteWhiteUsed` is true.
- `LOCK_PENDING` — `UndoLastCrossAction` if a cross was made this turn, `EndTurnAction` to acknowledge without undoing.

**Passive player actions by phase:**
- `PASSIVE_MOVE` — `CrossCellAction` for cells reachable via white+white only, and `EndTurnAction` to pass (which costs a punishment). Passive players are never offered `GiveUpAction`.
- `LOCK_PENDING` — same acknowledgement options as the active player.

### Cell reachability

For each open row, `rightmostCrossedPosition` is derived as the maximum `cell.position` across all crossed cells (or `-1` when the row is empty). A cell is a candidate when its position is strictly greater than the rightmost.

Among candidates, the combination is resolved by the priority rule from `DESIGN.md`:
1. If `colorDieUsed` is already true, the cell is unavailable.
2. Try `WHITE_WHITE` first: if `!whiteWhiteUsed` and `white1 + white2` matches the cell's `displayValue`.
3. Otherwise try `WHITE_COLOR`: if a colored die for `cell.color` is still active and either `white1 + colorDie` or `white2 + colorDie` matches.
4. If neither applies, the cell is not included.

### Phase transitions

```
ROLL          → (RollAction)              ACTIVE_MOVE
                                          currentRoll set; passivePlayerQueue built from all players except active

ACTIVE_MOVE   → (EndTurnAction)           PASSIVE_MOVE  when passivePlayerQueue is non-empty
                                          EVALUATE      when passivePlayerQueue is empty
              → (DeclareLockIntentAction) LOCK_PENDING

LOCK_PENDING  → (CrossLockAction)         EVALUATE
                                          row closed, die removed, lock bonus added to closing player

PASSIVE_MOVE  → (last EndTurnAction)      EVALUATE

EVALUATE                                  isGameOver? → set gameOver flag and finish
                                          otherwise   → advance turn pointer, phase = ROLL
```

`EVALUATE` is not a waiting phase. It runs synchronously inside `apply` immediately after the last move of a turn and either ends the game or starts the next turn.

### Game-end conditions

The game ends when either of these becomes true after an action:
- Two or more rows are closed (`boardState.closedRows().size() >= 2`).
- Any player has four punishment crosses.

---

## Offline mode

When `GameSettings.gameMode == OFFLINE`, `ConfigurableGameStyleFactory.buildTurnRules()` wraps the variant's own `TurnRules` in `OfflineTurnRules`:

```java
public class OfflineTurnRules implements TurnRules {
  private final TurnRules base;   // StandardTurnRules or LongoTurnRules — used only for isGameOver

  @Override
  public List<GameAction> getValidActions(GameState state, UUID playerId) {
    // Any uncrossed cell that passes the player's progression check is a valid CrossCellAction.
    // Any row that meets lock pre-conditions is a valid CrossLockAction.
    // TakePunishmentAction is always valid.
    // No phase check. No dice check.
  }

  @Override
  public GameState apply(GameState state, GameAction action) {
    return switch (action) {
      case CrossCellAction a   -> applyCross(state, a);    // progression-only guard; AutoCross tags still fire
      case CrossLockAction a   -> applyLock(state, a);     // lock pre-conditions guard
      case TakePunishmentAction a -> applyPunishment(state, a);
      default -> throw new IllegalMoveException("action not valid in offline mode");
    };
    // After applying, check isGameOver and set gameOver flag if true.
  }

  @Override
  public boolean isGameOver(GameState state) {
    return state.boardState().closedRows().size() >= 2
        || state.boardState().sheetProgress().values().stream()
               .anyMatch(p -> p.punishments() >= MAX_PUNISHMENTS);
  }
}
```

`TurnState` is `null` for the full lifetime of an offline session. `GameState.turnState()` returns `null`; all callers that would normally read `TurnState` (delegates, mappers) must guard on `settings.gameMode()`.

`GameStateDto` omits turn-state fields (`currentRoll`, `activePlayerId`, `phase`, `passivePlayerQueue`) when `gameMode == OFFLINE`, since they carry no meaning and would be `null` anyway.

`TakePunishmentAction` is added to the `GameAction` sealed interface. The `MovesApiDelegateImpl` mapper maps move type `TAKE_PUNISHMENT` to this action. The action is rejected by `StandardTurnRules` and `LongoTurnRules` (online mode) — only `OfflineTurnRules` accepts it.

---

## ScoringEngine

`ScoringEngine.calculate(SheetLayout, SheetProgress)` is a pure function — no state, no side effects. It needs both objects because the tags that affect scoring (`ExtraBucket`, `DoubleCross`, `BonusPoints`) live on the cell definitions in `SheetLayout`, not in `SheetProgress`.

`ScoreCard.total()` is always derived on the fly rather than stored, preventing a second source of truth.

---

## QwixxGameOptions

This class is the single source of truth for all configurable game options. It has two responsibilities:

`all()` returns the list of `GameOption` objects that the `/game-options` endpoint exposes — type, label, description, default value, and valid choices. This is what the lobby UI reads to render the options form.

`apply(GameSettings.Builder, Map<String, Object>)` maps the option key-value pairs from a `NewGameRequest` onto a `GameSettings` builder. Unknown keys are logged and ignored. Each option has exactly one `case` here and one entry in `all()`.

```java
public class QwixxGameOptions {

  public static List<GameOption> all() {
    return List.of(
        new GameOption("gameMode", "Game mode",
            "ONLINE enforces turn order and dice rules on the server. " +
            "OFFLINE lets players use real dice — the server only tracks crossings and game-end conditions.",
            TypeEnum.ENUM, "ONLINE")
            .choices(List.of("ONLINE", "OFFLINE")),
        new GameOption("cardMode", "Card mode",
            "SAME_CARDS gives all players identical row layouts. " +
            "DIFFERENT_CARDS gives each player their own random layout.",
            TypeEnum.ENUM, "SAME_CARDS")
            .choices(List.of("SAME_CARDS", "DIFFERENT_CARDS")),
        new GameOption("randomOrder", "Random order",
            "Shuffle the display values within each row.",
            TypeEnum.BOOLEAN, "false"),
        new GameOption("extraRow", "Extra row",
            "Add ExtraBucket-tagged cells forming a sinus wave across the four rows.",
            TypeEnum.BOOLEAN, "false")
    );
  }

  public static void apply(GameSettings.Builder builder, Map<String, Object> options) {
    if (options == null) return;
    for (var entry : options.entrySet()) {
      switch (entry.getKey()) {
        case "gameMode"     -> builder.gameMode(GameMode.valueOf(toString(entry.getValue())));
        case "cardMode"     -> builder.cardMode(CardMode.valueOf(toString(entry.getValue())));
        case "randomOrder"  -> builder.randomOrder(toBoolean(entry.getValue()));
        case "extraRow"     -> builder.extraRow(toBoolean(entry.getValue()));
        default             -> log("unknown option '" + entry.getKey() + "', ignoring");
      }
    }
  }
}
```

---

## Delegate implementations

Each delegate is thin. It resolves the session from `GameRegistry`, maps the request to a domain object, delegates to `GameSession`, and maps the result to a DTO. No game logic belongs here.

### GamesApiDelegateImpl

- `createNewGame` — validates the room name, creates a session via `GameRegistry.createGame`, applies game options via `QwixxGameOptions.apply`, returns the session ID.
- `startGame` — returns `409` if the session is already in progress, otherwise calls `session.start()`.
- `stopGame` — calls `GameRegistry.removeGame`; the session is garbage-collected.
- `getAllGames` — delegates to `GameRegistry.getAllGames()`, which reads each session's metadata and status.

### PlayersApiDelegateImpl

- `addPlayerToGame` — rejects if `status != WAITING` or `players.size() >= maxPlayers`, otherwise appends the player and increments the version.
- `leaveGame` — removes the player; if all players have left, removes the session from the registry entirely.

### MovesApiDelegateImpl

`makeMove` is the core of the server. The flow is:

1. Look up the session; return `404` if missing.
2. Verify the game has started; return `409` otherwise.
3. Map `MoveRequestDto` → `GameAction` via the mapper (e.g. `CROSS_WHITE_WHITE` → a `CrossCellAction` with `DiceCombination.WHITE_WHITE` and the provided `cellId`).
4. Check that the action appears in `session.getValidActions(playerId)`; return `400` with an `ErrorResponseDto` if not.
5. Call `session.applyAction(action)`, which is synchronized and returns the new `GameState`.
6. Map the result to a `MoveResponseDto` and return `200`.

### GameStatesApiDelegateImpl

Returns the full game state or `304` when the client is already up to date:

```java
GameState state = session.currentState();
if (stateVersion != null && stateVersion == state.version()) {
  return ResponseEntity.status(304).build();
}
return ResponseEntity.ok(mapper.toDto(state));
```

The version is an `AtomicLong` on `GameState`. It increments on every `applyAction`. The client polls at ~500 ms and re-renders only when the version changes. No WebSocket infrastructure is needed for a turn-based local game.

---

## DTO mapping

DTOs mirror the OpenAPI schemas exactly and are what Spring serializes. Domain objects are never serialized directly.

The mapper's job is to translate between the two representations. Key decisions:
- `GameStateDto` omits `moveStartProgress` (internal snapshot used only for reset) and the raw `SheetLayout` definitions (the client doesn't need the full layout in every poll response — it is sent once at game start).
- `SheetProgressDto` uses row ID strings as map keys rather than integer indices, since the client identifies rows by string ID.
- `MoveRequestDto.moveType` maps to the matching `GameAction` subtype. `ROLL` → `RollAction`, `CROSS_WHITE_WHITE` → `CrossCellAction(WHITE_WHITE, cellId, rowId)`, and so on.

---

## Error handling

A `@RestControllerAdvice` converts domain exceptions to `ErrorResponseDto` at a single place. Delegates throw typed exceptions; the advice maps each type to an HTTP status. Delegates do not catch exceptions themselves.

| Exception | Status |
|---|---|
| `SessionNotFoundException` | 404 |
| `IllegalMoveException` | 400 |
| `GameAlreadyStartedException` | 409 |
| `GameNotStartedException` | 409 |

---

## CORS

In production, Spring Boot serves the Angular build output as static files on port 4300. Both client and server share the same origin, so no CORS configuration is needed.

CORS only matters during development when running `ng serve`, which starts Angular on its own port. In that case a `WebMvcConfigurer` can allow that origin temporarily, or an Angular proxy configuration can forward API calls to the Spring Boot port so the browser never makes a cross-origin request.

---

## Testing

The game engine (`action`, `rules`, `game`) has no Spring dependency and can be tested with plain JUnit — no application context needed. This is the most valuable place to invest in tests because the rules are complex and the engine is deterministic.

The web layer is tested with `@WebMvcTest`, which loads only the controller slice with a mocked `GameRegistry`. These tests verify that the HTTP contract (status codes, response shape, error cases) matches the OpenAPI spec.