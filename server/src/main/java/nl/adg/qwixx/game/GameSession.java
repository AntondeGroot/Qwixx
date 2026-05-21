package nl.adg.qwixx.game;

import static java.util.Collections.shuffle;

import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.bot.BotDecider;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.rules.ScoreCard;
import nl.adg.qwixx.rules.TurnRules;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class GameSession {

    /** How long to pause after a bot rolls dice so the client animation can play. */
    private static final long BOT_ROLL_DELAY_MS = 3_000;

    private static final Logger log = Logger.getLogger(GameSession.class.getName());

    private final String      sessionId;
    private final String      roomName;
    private final int         maxPlayers;
    private       GameSettings settings;
    private       TurnRules   rules;
    private volatile Map<String, Object> proposedOptions = new HashMap<>();
    private final List<Player> players = new ArrayList<>();
    private final Set<UUID>    botPlayerIds  = new LinkedHashSet<>();
    private final Map<UUID, nl.adg.qwixx.bot.BotProfile> botProfiles = new HashMap<>();
    private       GameState   state;
    private       SessionStatus status = SessionStatus.WAITING;

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

    private synchronized int effectiveBotCount() {
        Object val = proposedOptions.get("botCount");
        return val instanceof Number n ? n.intValue() : settings.botCount();
    }

    public synchronized void removePlayer(UUID playerId) {
        if (status == SessionStatus.IN_PROGRESS)
            throw new IllegalStateException("cannot remove players while game is in progress");
        boolean removed = players.removeIf(p -> p.id().equals(playerId));
        if (!removed) throw new IllegalArgumentException("player not found: " + playerId);
    }

    public synchronized void start() {
        if (status != SessionStatus.WAITING)
            throw new IllegalStateException("game already started");

        // Apply options that were changed in the lobby after the game was created
        if (!proposedOptions.isEmpty()) {
            GameSettings.Builder builder = GameSettings.builder()
                    .base(settings.base()).randomOrder(settings.randomOrder())
                    .connectedCells(settings.connectedCells()).extraRow(settings.extraRow())
                    .mixedColors(settings.mixedColors()).cardMode(settings.cardMode())
                    .gameMode(settings.gameMode()).botCount(settings.botCount())
                    .botStrategy(settings.botStrategy());
            QwixxGameOptions.apply(builder, proposedOptions);
            settings = builder.build();
        }

        if (players.isEmpty() && settings.botCount() == 0)
            throw new IllegalStateException("cannot start with no players");

        for (int i = 0; i < settings.botCount(); i++) {
            Player bot = Player.of("Computer " + (i + 1));
            players.add(bot);
            botPlayerIds.add(bot.id());
            botProfiles.put(bot.id(), settings.profileForBot(i));
        }

        shuffle(players);

        GameStyleFactory factory = new ConfigurableGameStyleFactory(settings);
        List<UUID> playerIds = players.stream().map(Player::id).toList();

        Map<UUID, List<Row>> rowsByPlayer = factory.buildRows(playerIds);

        // Reuse the same SheetLayout instance when players share the same row list (DETERMINISTIC mode)
        Map<List<Row>, SheetLayout> layoutCache = new HashMap<>();
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        for (UUID id : playerIds) {
            List<Row> rows = rowsByPlayer.get(id);
            layouts.put(id, layoutCache.computeIfAbsent(rows, SheetLayout::new));
        }

        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID id : playerIds) {
            Map<Integer, RowState> rowStates = new HashMap<>();
            List<Row> rows = rowsByPlayer.get(id);
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
        state = runBotTurns(state);
        if (state.gameOver()) status = SessionStatus.FINISHED;
    }

    public synchronized GameState applyAction(GameAction action) {
        return applyAction(action, null);
    }

    /**
     * Applies a player action and runs any subsequent bot turns.
     * If {@code botStateListener} is provided it is called (and the thread sleeps
     * for {@link #BOT_ROLL_DELAY_MS}) immediately after each bot roll so the client
     * can play the dice animation before the bot's moves arrive.
     * Pass {@code null} for headless simulation (no delay, no intermediate emit).
     */
    public synchronized GameState applyAction(GameAction action, Consumer<GameState> botStateListener) {
        if (status != SessionStatus.IN_PROGRESS)
            throw new IllegalStateException("game is not in progress");
        state = rules.apply(state, action);
        if (!state.gameOver()) state = runBotTurns(state, botStateListener);
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
                .calculate(state.sheetLayouts().get(playerId),
                           state.boardState().sheetProgress().get(playerId));
    }

    /** Returns only the human (non-bot) players. */
    public synchronized List<Player> humanPlayers() {
        return players.stream().filter(p -> !botPlayerIds.contains(p.id())).toList();
    }

    public synchronized void restart(GameSettings newSettings) {
        if (status != SessionStatus.FINISHED)
            throw new IllegalStateException("can only restart a finished game");

        this.settings = newSettings;

        // Drop bots from the previous game; fresh ones will be added below.
        players.removeIf(p -> botPlayerIds.contains(p.id()));
        botPlayerIds.clear();
        botProfiles.clear();

        for (int i = 0; i < newSettings.botCount(); i++) {
            Player bot = Player.of("Computer " + (i + 1));
            players.add(bot);
            botPlayerIds.add(bot.id());
            botProfiles.put(bot.id(), newSettings.profileForBot(i));
        }

        shuffle(players);

        GameStyleFactory factory = new ConfigurableGameStyleFactory(newSettings);
        List<UUID> playerIds = players.stream().map(Player::id).toList();

        Map<UUID, List<Row>> rowsByPlayer = factory.buildRows(playerIds);

        Map<List<Row>, SheetLayout> layoutCache = new HashMap<>();
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        for (UUID id : playerIds) {
            List<Row> rows = rowsByPlayer.get(id);
            layouts.put(id, layoutCache.computeIfAbsent(rows, SheetLayout::new));
        }

        Map<UUID, SheetProgress> progress = new HashMap<>();
        for (UUID id : playerIds) {
            Map<Integer, RowState> rowStates = new HashMap<>();
            List<Row> rows = rowsByPlayer.get(id);
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
        state = runBotTurns(state);
        if (state.gameOver()) status = SessionStatus.FINISHED;
    }

    public synchronized void forceFinish() {
        state.setGameOver(true);
        state.incrementVersion();
        status = SessionStatus.FINISHED;
    }

    private GameState runBotTurns(GameState state) {
        return runBotTurns(state, null);
    }

    private GameState runBotTurns(GameState state, Consumer<GameState> botStateListener) {
        if (botPlayerIds.isEmpty()) return state;
        int guard = 0;
        while (!state.gameOver() && guard++ < 200) {
            UUID bot = nextBotToAct(state);
            if (bot == null) break;
            List<GameAction> valid = rules.getValidActions(state, bot);
            if (valid.isEmpty()) break;
            GameAction action = BotDecider.decide(state, bot, valid,
                    botProfiles.getOrDefault(bot, nl.adg.qwixx.bot.BotProfile.DEFAULT));
            state = rules.apply(state, action);

            if (action instanceof RollAction && botStateListener != null) {
                botStateListener.accept(state);
                try {
                    Thread.sleep(BOT_ROLL_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warning("Bot roll delay interrupted");
                }
            }
        }
        return state;
    }

    private UUID nextBotToAct(GameState state) {
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

    public Map<String, Object> proposedOptions()                    { return Map.copyOf(proposedOptions); }
    public void setProposedOptions(Map<String, Object> opts)        { proposedOptions = new HashMap<>(opts); }
}