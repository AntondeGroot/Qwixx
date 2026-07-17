package nl.adg.qwixx.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.game.QwixxGameOptions;
import nl.adg.qwixx.game.SessionStatus;
import nl.adg.qwixx.game.exception.GameAlreadyStartedException;
import nl.adg.qwixx.game.exception.GameNotFinishedException;
import nl.adg.qwixx.game.exception.SessionNotFoundException;
import nl.adg.qwixx.generated.api.GamesApiDelegate;
import nl.adg.qwixx.generated.model.AddPlayerToGame201Response;
import nl.adg.qwixx.generated.model.CreateNewGame201Response;
import nl.adg.qwixx.generated.model.GameInfo;
import nl.adg.qwixx.generated.model.GameStatus;
import nl.adg.qwixx.generated.model.NewGameRequest;
import nl.adg.qwixx.generated.model.RestartGameRequest;
import nl.adg.qwixx.generated.model.ScoreCard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class GamesApiDelegateImpl implements GamesApiDelegate {

    /** How long the initial bot-turn runner waits for a client to subscribe before rolling anyway. */
    private static final long SUBSCRIBER_WAIT_MS = 5_000;

    @Autowired
    private SseEmitterRegistry sseRegistry;

    @Autowired
    private LobbyController lobbyController;

    @Autowired
    private GameFinishedNotifier gameFinishedNotifier;

    /** Runs initial bot turns off the request thread so the roll can animate on connected clients. */
    private final ExecutorService botTurnExecutor = Executors.newCachedThreadPool();

    @Override
    public ResponseEntity<CreateNewGame201Response> createNewGame(NewGameRequest req) {
        String id = GameRegistry.createGame(req.getRoomName(), req.getMaxPlayers(),
                buildSettings(req.getGameOptions()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateNewGame201Response().sessionId(id));
    }

    @Override
    public ResponseEntity<List<GameInfo>> getAllGames() {
        return ResponseEntity.ok(GameRegistry.getAllGames().stream().map(this::toGameInfo).toList());
    }

    @Override
    public ResponseEntity<GameInfo> getGame(String sessionId) {
        return ResponseEntity.ok(toGameInfo(require(sessionId)));
    }

    @Override
    public ResponseEntity<Void> startGame(String sessionId,
            nl.adg.qwixx.generated.model.StartGameRequest req) {
        GameSession session = require(sessionId);
        List<Integer> botPics = (req != null && req.getBotProfilePics() != null)
                ? req.getBotProfilePics() : List.of();
        if (req != null) {
            gameFinishedNotifier.register(sessionId, req.getCallbackUrl());
        }
        try {
            // Defer initial bot turns (see runInitialBotTurnsAsync) so a bot going first plays
            // its dice animation once clients have subscribed, rather than the roll being baked in.
            session.start(botPics, false);
        } catch (IllegalStateException ex) {
            throw new GameAlreadyStartedException(sessionId, ex);
        }
        sseRegistry.emit(sessionId, session.currentState(), session);
        runInitialBotTurnsAsync(sessionId, session);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> restartGame(String sessionId, RestartGameRequest req) {
        GameSession session = require(sessionId);
        try {
            session.restart(buildSettings(resolveOptions(req, session)), false);
        } catch (IllegalStateException ex) {
            throw new GameNotFinishedException(sessionId, ex);
        }
        sseRegistry.emit(sessionId, session.currentState(), session);
        runInitialBotTurnsAsync(sessionId, session);
        return ResponseEntity.ok().build();
    }

    /**
     * Runs any bot turns pending at the start of a game on a background thread, after waiting
     * (briefly) for a client to subscribe to the SSE stream. This lets a bot that acts first
     * play its dice-roll animation instead of the roll appearing pre-rolled in the initial state.
     * No-op when a human acts first.
     */
    private void runInitialBotTurnsAsync(String sessionId, GameSession session) {
        if (!session.isBotToAct()) return;
        botTurnExecutor.submit(() -> {
            awaitSubscriber(sessionId);
            session.runPendingBotTurns(
                    intermediate -> sseRegistry.emit(sessionId, intermediate, session));
            sseRegistry.emit(sessionId, session.currentState(), session);
            gameFinishedNotifier.checkAndNotify(sessionId, session);
        });
    }

    private void awaitSubscriber(String sessionId) {
        long deadline = System.currentTimeMillis() + SUBSCRIBER_WAIT_MS;
        while (sseRegistry.subscriberCount(sessionId) == 0
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public ResponseEntity<Void> stopGame(String sessionId) {
        require(sessionId);
        GameRegistry.removeGame(sessionId);
        sseRegistry.completeAll(sessionId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AddPlayerToGame201Response> addPlayerToGame(String sessionId,
            nl.adg.qwixx.generated.model.Player req) {
        GameSession session = require(sessionId);
        Player player = playerFromRequest(req);
        try {
            session.addPlayer(player);
        } catch (IllegalStateException ex) {
            throw new GameAlreadyStartedException(sessionId, ex);
        }
        lobbyController.emitLobby(sessionId, session);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AddPlayerToGame201Response().playerId(player.id().toString()));
    }

    @Override
    public ResponseEntity<List<nl.adg.qwixx.generated.model.Player>> getAllPlayersInGame(
            String sessionId) {
        return ResponseEntity.ok(require(sessionId).players().stream()
                .map(this::toPlayerDto)
                .toList());
    }

    @Override
    public ResponseEntity<Void> leaveGame(String sessionId, String playerId) {
        GameSession session = require(sessionId);
        try {
            if (session.status() == SessionStatus.IN_PROGRESS) {
                session.exitGame(UUID.fromString(playerId));
            } else {
                session.removePlayer(UUID.fromString(playerId));
            }
        } catch (IllegalArgumentException ex) {
            throw new SessionNotFoundException(playerId, ex);
        }
        lobbyController.emitLobby(sessionId, session);
        gameFinishedNotifier.checkAndNotify(sessionId, session);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, ScoreCard>> getScores(String sessionId) {
        GameSession session = require(sessionId);
        if (session.status() != SessionStatus.FINISHED)
            throw new GameNotFinishedException(sessionId);

        // Iterate the game-state player list (all who participated) rather than
        // session.players() which may be smaller if players left after the game ended.
        Map<String, ScoreCard> scores = session.currentState().players().stream()
                .collect(Collectors.toMap(UUID::toString, id -> toDto(session.getScore(id))));
        return ResponseEntity.ok(scores);
    }

    // ── Converters ────────────────────────────────────────────────────────────

    private static GameSettings buildSettings(Map<String, Object> options) {
        GameSettings.Builder builder = GameSettings.builder();
        QwixxGameOptions.apply(builder, options);
        return builder.build();
    }

    private static Map<String, Object> resolveOptions(RestartGameRequest req, GameSession session) {
        return (req != null && req.getGameOptions() != null)
                ? new HashMap<>(req.getGameOptions())
                : session.proposedOptions();
    }

    private static Player playerFromRequest(nl.adg.qwixx.generated.model.Player req) {
        UUID id = req.getId() != null && !req.getId().isBlank()
                ? UUID.fromString(req.getId())
                : UUID.randomUUID();
        return new Player(id, req.getName(), req.getProfilePic());
    }

    private nl.adg.qwixx.generated.model.Player toPlayerDto(Player p) {
        return new nl.adg.qwixx.generated.model.Player(p.id().toString(), p.name())
                .profilePic(p.profilePic());
    }

    private static ScoreCard toDto(nl.adg.qwixx.rules.ScoreCard sc) {
        ScoreCard dto = new ScoreCard(
                toStringKeyedMap(sc.crossesPerColor()),
                toStringKeyedMap(sc.pointsPerColor()),
                sc.extraCrosses(), sc.extraPoints(),
                sc.bonusPoints(), sc.punishmentPoints(), sc.total());
        dto.setNoPenalty(sc.noPenalty());
        if (sc.doubledColor() != null) {
            dto.setDoubledColor(nl.adg.qwixx.generated.model.Color.fromValue(sc.doubledColor().name()));
        }
        return dto;
    }

    private static <K> Map<String, Integer> toStringKeyedMap(Map<K, Integer> map) {
        return map.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toString(), Map.Entry::getValue));
    }

    private GameSession require(String sessionId) {
        GameSession session = GameRegistry.getGame(sessionId);
        if (session == null) throw new SessionNotFoundException(sessionId);
        return session;
    }

    private GameInfo toGameInfo(GameSession session) {
        return new GameInfo(
                session.sessionId(),
                session.roomName(),
                session.players().size(),
                session.maxPlayers(),
                toGameStatus(session));
    }

    private GameStatus toGameStatus(GameSession session) {
        return switch (session.status()) {
            case WAITING     -> GameStatus.WAITING;
            case IN_PROGRESS -> GameStatus.IN_PROGRESS;
            case FINISHED    -> GameStatus.FINISHED;
        };
    }
}
