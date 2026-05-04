package nl.adg.qwixx.game;

import nl.adg.qwixx.action.GameAction;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GameSession {

    private final String      sessionId;
    private final String      roomName;
    private final int         maxPlayers;
    private       GameSettings settings;
    private       TurnRules   rules;
    private volatile Map<String, Object> proposedOptions = new HashMap<>();
    private final List<Player> players = new ArrayList<>();
    private       GameState   state;
    private       SessionStatus status = SessionStatus.WAITING;

    public GameSession(String sessionId, String roomName, int maxPlayers, GameSettings settings) {
        this.sessionId  = sessionId;
        this.roomName   = roomName;
        this.maxPlayers = maxPlayers;
        this.settings   = settings;
    }

    public void addPlayer(Player player) {
        if (status != SessionStatus.WAITING)
            throw new IllegalStateException("cannot add players after game has started");
        if (players.size() >= maxPlayers)
            throw new IllegalStateException("session is full");
        players.add(player);
    }

    public void removePlayer(UUID playerId) {
        if (status == SessionStatus.IN_PROGRESS)
            throw new IllegalStateException("cannot remove players while game is in progress");
        boolean removed = players.removeIf(p -> p.id().equals(playerId));
        if (!removed) throw new IllegalArgumentException("player not found: " + playerId);
    }

    public synchronized void start() {
        if (status != SessionStatus.WAITING)
            throw new IllegalStateException("game already started");
        if (players.isEmpty())
            throw new IllegalStateException("cannot start with no players");

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
    }

    public synchronized GameState applyAction(GameAction action) {
        if (status != SessionStatus.IN_PROGRESS)
            throw new IllegalStateException("game is not in progress");
        state = rules.apply(state, action);
        if (state.gameOver()) status = SessionStatus.FINISHED;
        return state;
    }

    public synchronized GameState currentState() {
        return state;
    }

    public ScoreCard getScore(UUID playerId) {
        if (state == null) throw new IllegalStateException("game not started");
        return new ConfigurableGameStyleFactory(settings)
                .buildScoringEngine()
                .calculate(state.sheetLayouts().get(playerId),
                           state.boardState().sheetProgress().get(playerId));
    }

    public synchronized void restart(GameSettings newSettings) {
        if (status != SessionStatus.FINISHED)
            throw new IllegalStateException("can only restart a finished game");

        this.settings = newSettings;
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
    }

    public synchronized void forceFinish() {
        state.setGameOver(true);
        state.incrementVersion();
        status = SessionStatus.FINISHED;
    }

    public String sessionId()        { return sessionId; }
    public String roomName()         { return roomName; }
    public int maxPlayers()          { return maxPlayers; }
    public GameSettings settings()   { return settings; }
    public List<Player> players()    { return List.copyOf(players); }
    public SessionStatus status()    { return status; }

    public Map<String, Object> proposedOptions()                    { return Map.copyOf(proposedOptions); }
    public void setProposedOptions(Map<String, Object> opts)        { proposedOptions = new HashMap<>(opts); }
}