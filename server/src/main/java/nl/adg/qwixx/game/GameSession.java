package nl.adg.qwixx.game;

import static java.util.Collections.shuffle;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Logger;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.bot.BotDecider;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.factory.ConfigurableGameStyleFactory;
import nl.adg.qwixx.game.factory.GameStyleFactory;
import nl.adg.qwixx.game.options.GameMode;
import nl.adg.qwixx.game.options.GameOptionCatalog;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.rules.ScoreCard;
import nl.adg.qwixx.rules.TurnRules;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

// `rules` and `state` are populated in start()/restart(), not the constructor.
@SuppressWarnings("NullAway.Init")
public class GameSession {

    /** A bot "thinks" for a random spell in this range before rolling, so its turn feels human. */
    private static final long BOT_PRE_ROLL_MIN_MS = 1_000;
    private static final long BOT_PRE_ROLL_MAX_MS = 2_000;

    /** How long to pause after a bot rolls dice so the client animation can play. */
    private static final long BOT_ROLL_DELAY_MS = 3_000;

    /** A short, random beat after each non-roll bot move (cross, punishment, lock, pass) so moves
     *  don't land instantaneously. Kept brief so several crosses in one turn stay snappy. */
    private static final long BOT_MOVE_MIN_MS = 800;
    private static final long BOT_MOVE_MAX_MS = 1_600;

    private static final Logger log = Logger.getLogger(GameSession.class.getName());

    private final String      sessionId;
    private final String      roomName;
    private final int         maxPlayers;
    private       GameSettings settings;
    private       TurnRules   rules;
    private volatile Map<String, Object> proposedOptions = new HashMap<>();
    private final List<Player> players = new ArrayList<>();
    private final Set<UUID>    botPlayerIds  = new LinkedHashSet<>();
    private final Set<UUID>    leftPlayerIds = new LinkedHashSet<>();
    private final Map<UUID, nl.adg.qwixx.bot.BotProfile> botProfiles = new HashMap<>();
    private       GameState   state;
    private       SessionStatus status = SessionStatus.WAITING;
    /** When false, bot pacing sleeps are skipped (tests only) so the paced path runs instantly. */
    private volatile boolean  botPacingEnabled = true;
    /** Drives player-order/pic shuffles and (via the factory) dice rolls; seedable for tests. */
    private       Random      random = new Random();
    /** Full pool of available bot profile-pic indices, as supplied by GameRoom at start time. */
    private List<Integer> botPicPool = List.of();

    public GameSession(String sessionId, String roomName, int maxPlayers, GameSettings settings) {
        this.sessionId  = sessionId;
        this.roomName   = roomName;
        this.maxPlayers = maxPlayers;
        this.settings   = settings;
    }

    public synchronized void addPlayer(Player player) {
        if (status != SessionStatus.WAITING)
            throw new IllegalStateException("cannot add players after game has started");
        if (players.size() >= maxPlayers)
            throw new IllegalStateException("session is full");
        players.add(player);
    }

    public synchronized void removePlayer(UUID playerId) {
        if (status == SessionStatus.IN_PROGRESS)
            throw new IllegalStateException("cannot remove players while game is in progress");
        boolean removed = players.removeIf(p -> p.id().equals(playerId));
        if (!removed) throw new IllegalArgumentException("player not found: " + playerId);
    }

    /** Called when a human player leaves during an in-progress game. Ends the game only when all humans have left. */
    public synchronized void exitGame(UUID playerId) {
        boolean known = players.stream().anyMatch(p -> p.id().equals(playerId));
        if (!known) throw new IllegalArgumentException("player not found: " + playerId);
        leftPlayerIds.add(playerId);
        boolean allGone = humanPlayers().stream().allMatch(p -> leftPlayerIds.contains(p.id()));
        if (allGone) forceFinish();
    }

    public synchronized void start() {
        start(List.of());
    }

    public synchronized void start(List<Integer> availableBotPics) {
        start(availableBotPics, true);
    }

    /**
     * Starts the game: seeds bots, builds each player's sheet, and rolls the first turn.
     *
     * @param runInitialBotTurns when {@code true} (headless/simulation) any bots that act
     *     first play immediately and synchronously. Live games pass {@code false} and drive
     *     the initial bot turns via {@link #driveBotTurns} once clients have subscribed,
     *     so a bot going first still plays its dice animation.
     */
    public synchronized void start(List<Integer> availableBotPics, boolean runInitialBotTurns) {
        if (status != SessionStatus.WAITING)
            throw new IllegalStateException("game already started");

        // Apply options that were changed in the lobby after the game was created
        if (!proposedOptions.isEmpty()) {
            GameSettings.Builder builder = GameSettings.builder()
                    .base(settings.base()).randomOrder(settings.randomOrder())
                    .connectedCells(settings.connectedCells()).extraRow(settings.extraRow())
                    .mixedColors(settings.mixedColors()).seeOtherCards(settings.seeOtherCards())
                    .cardMode(settings.cardMode())
                    .gameMode(settings.gameMode()).botCount(settings.botCount())
                    .botStrategy(settings.botStrategy());
            GameOptionCatalog.apply(builder, proposedOptions);
            settings = builder.build();
        }

        if (players.isEmpty() && settings.botCount() == 0)
            throw new IllegalStateException("cannot start with no players");

        botPicPool = List.copyOf(availableBotPics);
        List<Integer> shuffled = new ArrayList<>(botPicPool);
        shuffle(shuffled, random);
        for (int i = 0; i < settings.botCount(); i++) {
            String pic = i < shuffled.size() ? String.valueOf(shuffled.get(i)) : null;
            Player bot = new Player(UUID.randomUUID(), "Computer " + (i + 1), pic);
            players.add(bot);
            botPlayerIds.add(bot.id());
            botProfiles.put(bot.id(), settings.profileForBot(i));
        }

        shuffle(players, random);

        GameStyleFactory factory = new ConfigurableGameStyleFactory(settings, random);
        List<UUID> playerIds = players.stream().map(Player::id).toList();

        Map<UUID, List<Row>> rowsByPlayer = factory.buildRows(playerIds);

        // Reuse the same SheetLayout instance when players share the same row list (SAME_CARDS mode)
        Map<List<Row>, SheetLayout> layoutCache = new HashMap<>();
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        for (UUID id : playerIds) {
            List<Row> rows = Objects.requireNonNull(rowsByPlayer.get(id));
            layouts.put(id, layoutCache.computeIfAbsent(rows, SheetLayout::new));
        }

        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID id : playerIds) {
            Map<Integer, RowState> rowStates = new HashMap<>();
            List<Row> rows = Objects.requireNonNull(rowsByPlayer.get(id));
            for (int i = 0; i < rows.size(); i++) rowStates.put(i, new RowState(new HashSet<>(), false));
            progress.put(id, new SheetProgress(rowStates, 0));
        }

        List<Die> dice = new ArrayList<>(factory.buildDice());
        BoardState board = new BoardState(progress, dice, new HashMap<>());

        TurnState turn = null;
        if (settings.gameMode() != GameMode.OFFLINE) {
            turn = new TurnState();
            turn.setActivePlayerId(playerIds.get(0));
            turn.setPhase(TurnPhase.ROLL);
        }

        state = new GameState(settings.cardMode(), playerIds, factory.buildVariantData(playerIds), layouts, board, turn);
        rules = factory.buildTurnRules();
        status = SessionStatus.IN_PROGRESS;
        if (runInitialBotTurns) {
            state = runBotTurns(state);
            if (state.gameOver()) status = SessionStatus.FINISHED;
        }
    }

    /**
     * Applies a player action and runs any subsequent bot turns inline (no pacing, no emits).
     * For headless simulation and tests — the returned state already reflects the bot turns.
     * Live play uses {@link #applyPlayerAction} + {@link #driveBotTurns} instead, so a human's
     * click is never blocked behind bot pacing.
     */
    public synchronized GameState applyAction(GameAction action) {
        if (status != SessionStatus.IN_PROGRESS)
            throw new IllegalStateException("game is not in progress");
        state = rules.apply(state, action);
        if (!state.gameOver()) state = runBotTurns(state);
        if (state.gameOver()) status = SessionStatus.FINISHED;
        return state;
    }

    /**
     * Applies a single player action and returns the new state, WITHOUT running any subsequent
     * bot turns. Live callers apply the human's move with this (so the response returns at once)
     * and then drive the paced bot turns asynchronously via {@link #driveBotTurns}.
     */
    public synchronized GameState applyPlayerAction(GameAction action) {
        if (status != SessionStatus.IN_PROGRESS)
            throw new IllegalStateException("game is not in progress");
        state = rules.apply(state, action);
        if (state.gameOver()) status = SessionStatus.FINISHED;
        return state;
    }

    public synchronized GameState currentState() {
        return state;
    }

    public synchronized ScoreCard getScore(UUID playerId) {
        if (state == null) throw new IllegalStateException("game not started");
        return new ConfigurableGameStyleFactory(settings)
                .buildScoringEngine()
                .calculate(state.sheetLayout(playerId), state.sheetProgress(playerId));
    }

    /** Returns only the human (non-bot) players. */
    public synchronized List<Player> humanPlayers() {
        return players.stream().filter(p -> !botPlayerIds.contains(p.id())).toList();
    }

    public synchronized void restart(GameSettings newSettings) {
        restart(newSettings, true);
    }

    /**
     * Resets a finished game to a fresh start with new settings, keeping the same players.
     * See {@link #start(List, boolean)} for the meaning of {@code runInitialBotTurns}.
     */
    public synchronized void restart(GameSettings newSettings, boolean runInitialBotTurns) {
        if (status != SessionStatus.FINISHED)
            throw new IllegalStateException("can only restart a finished game");

        this.settings = newSettings;

        // Drop bots from the previous game; fresh ones will be added below.
        players.removeIf(p -> botPlayerIds.contains(p.id()));
        botPlayerIds.clear();
        botProfiles.clear();

        List<Integer> shuffled = new ArrayList<>(botPicPool);
        shuffle(shuffled, random);
        for (int i = 0; i < newSettings.botCount(); i++) {
            String pic = i < shuffled.size() ? String.valueOf(shuffled.get(i)) : null;
            Player bot = new Player(UUID.randomUUID(), "Computer " + (i + 1), pic);
            players.add(bot);
            botPlayerIds.add(bot.id());
            botProfiles.put(bot.id(), newSettings.profileForBot(i));
        }

        shuffle(players, random);

        GameStyleFactory factory = new ConfigurableGameStyleFactory(newSettings, random);
        List<UUID> playerIds = players.stream().map(Player::id).toList();

        Map<UUID, List<Row>> rowsByPlayer = factory.buildRows(playerIds);

        Map<List<Row>, SheetLayout> layoutCache = new HashMap<>();
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        for (UUID id : playerIds) {
            List<Row> rows = Objects.requireNonNull(rowsByPlayer.get(id));
            layouts.put(id, layoutCache.computeIfAbsent(rows, SheetLayout::new));
        }

        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID id : playerIds) {
            Map<Integer, RowState> rowStates = new HashMap<>();
            List<Row> rows = Objects.requireNonNull(rowsByPlayer.get(id));
            for (int i = 0; i < rows.size(); i++) rowStates.put(i, new RowState(new HashSet<>(), false));
            progress.put(id, new SheetProgress(rowStates, 0));
        }

        List<Die> dice = new ArrayList<>(factory.buildDice());
        BoardState board = new BoardState(progress, dice, new HashMap<>());

        TurnState turn = null;
        if (newSettings.gameMode() != GameMode.OFFLINE) {
            turn = new TurnState();
            turn.setActivePlayerId(playerIds.get(0));
            turn.setPhase(TurnPhase.ROLL);
        }

        state = new GameState(newSettings.cardMode(), playerIds, factory.buildVariantData(playerIds), layouts, board, turn);
        rules = factory.buildTurnRules();
        status = SessionStatus.IN_PROGRESS;
        if (runInitialBotTurns) {
            state = runBotTurns(state);
            if (state.gameOver()) status = SessionStatus.FINISHED;
        }
    }

    public synchronized void forceFinish() {
        state.setGameOver(true);
        state.incrementVersion();
        status = SessionStatus.FINISHED;
    }

    /** Headless bot turns: applies every pending bot action inline with no pacing or emits. */
    private GameState runBotTurns(GameState state) {
        if (botPlayerIds.isEmpty()) return state;
        GameState current = state;
        int guard = 0;
        while (!current.gameOver() && guard < 200) {
            guard++;
            UUID bot = nextBotToAct(current);
            if (bot == null) break;
            List<GameAction> valid = rules.getValidActions(current, bot);
            if (valid.isEmpty()) break;
            GameAction action = BotDecider.decide(current, bot, valid,
                    botProfiles.getOrDefault(bot, nl.adg.qwixx.bot.BotProfile.DEFAULT));
            current = rules.apply(current, action);
        }
        return current;
    }

    /**
     * True when a bot is next to act at the current state (e.g. a bot rolls first). False once the
     * game is over or not in progress — kept consistent with {@link #stepBotOnce} so the driver's
     * idle re-check can never spin on a game-over state.
     */
    public synchronized boolean isBotToAct() {
        return status == SessionStatus.IN_PROGRESS && !state.gameOver() && nextBotToAct(state) != null;
    }

    /**
     * Drives all pending bot turns with human-feel pacing, emitting each intermediate state through
     * {@code emit}. Runs OFF the request thread (see the web layer's bot driver): only the per-action
     * state mutation and its emit are synchronized (in {@link #stepBotOnce}); the pacing delay between
     * actions holds no lock, so a human's move can interleave and is never blocked behind bot pacing.
     */
    public void driveBotTurns(Consumer<GameState> emit) {
        for (int guard = 0; guard < 400; guard++) {
            Long delayMs = stepBotOnce(emit);
            if (delayMs == null) return; // no bot pending (or game over)
            pauseBot(delayMs);           // UNLOCKED — human moves interleave here
        }
    }

    /**
     * Applies the next pending bot action in a short critical section, emitting the resulting state
     * (and, for a roll, the pre-roll board first) while the lock is held so each snapshot is
     * consistent. Returns the delay to pace before the next action, or {@code null} when no bot is
     * pending. During a bot's ROLL phase no other player has a legal move, so the brief pre-roll
     * "think" pause here is the one place pacing runs under the lock — it never blocks a human move.
     */
    private synchronized @Nullable Long stepBotOnce(Consumer<GameState> emit) {
        if (status != SessionStatus.IN_PROGRESS || state.gameOver()) return null;
        UUID bot = nextBotToAct(state);
        if (bot == null) return null;
        List<GameAction> valid = rules.getValidActions(state, bot);
        if (valid.isEmpty()) return null;
        GameAction action = BotDecider.decide(state, bot, valid,
                botProfiles.getOrDefault(bot, nl.adg.qwixx.bot.BotProfile.DEFAULT));
        boolean isRoll = action instanceof RollAction;

        if (isRoll) {
            // Emit the bot's cleared, pre-roll board and let it "think" before rolling. Clients drive
            // the dice animation off the no-roll -> roll transition, so this pre-roll emit is what
            // makes the animation play for a bot.
            emit.accept(state);
            pauseBot(randomPreRollDelayMs());
        }
        state = rules.apply(state, action);
        if (state.gameOver()) status = SessionStatus.FINISHED;
        emit.accept(state);

        // The roll gets a long, fixed pause for the dice animation; every other move (cross,
        // punishment, lock intent, pass) gets a short random beat so it doesn't land instantaneously.
        return isRoll ? BOT_ROLL_DELAY_MS : randomMoveDelayMs();
    }

    private static long randomPreRollDelayMs() {
        return ThreadLocalRandom.current().nextLong(BOT_PRE_ROLL_MIN_MS, BOT_PRE_ROLL_MAX_MS + 1);
    }

    private static long randomMoveDelayMs() {
        return ThreadLocalRandom.current().nextLong(BOT_MOVE_MIN_MS, BOT_MOVE_MAX_MS + 1);
    }

    private void pauseBot(long millis) {
        if (!botPacingEnabled) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warning("Bot roll delay interrupted");
        }
    }

    /** Test hook: disables the human-feel bot pacing so paced-path tests run instantly. */
    void disableBotPacingForTest() {
        this.botPacingEnabled = false;
    }

    /**
     * Test hook: makes the whole game reproducible — seeds the shuffle/dice RNG for this session and
     * the bot's tie-breaking RNG — so bot-driven runs give deterministic coverage and mutation results.
     */
    synchronized void seedForTest(long seed) {
        this.random = new Random(seed);
        BotDecider.seedForTest(seed);
    }

    @Nullable private UUID nextBotToAct(GameState state) {
        TurnState turn = state.turnState();
        if (turn == null) return null;
        TurnPhase phase = turn.phase();
        UUID active = turn.activePlayerId();

        if (botPlayerIds.contains(active)) {
            if (phase == TurnPhase.ROLL || phase == TurnPhase.ACTIVE_MOVE) return active;
        }

        List<UUID> queue = turn.passivePlayerQueue();
        if (queue != null) {
            for (UUID pid : queue) {
                if (botPlayerIds.contains(pid) && !rules.getValidActions(state, pid).isEmpty()) {
                    return pid;
                }
            }
        }

        return null;
    }

    public String sessionId()                      { return sessionId; }
    public String roomName()                       { return roomName; }
    public int maxPlayers()                        { return maxPlayers; }
    public synchronized GameSettings settings()    { return settings; }
    public synchronized List<Player> players()     { return List.copyOf(players); }
    public synchronized SessionStatus status()     { return status; }
    public synchronized TurnRules rules()          { return rules; }

    public Map<String, Object> proposedOptions()                    { return Map.copyOf(proposedOptions); }
    public void setProposedOptions(Map<String, Object> opts)        { proposedOptions = new HashMap<>(opts); }
}
