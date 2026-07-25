package nl.adg.qwixx.web;

import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.game.GameRegistry;
import nl.adg.qwixx.game.GameSession;
import nl.adg.qwixx.game.Player;
import nl.adg.qwixx.game.SessionStatus;
import nl.adg.qwixx.game.exception.GameAlreadyStartedException;
import nl.adg.qwixx.game.exception.GameNotFinishedException;
import nl.adg.qwixx.game.exception.SessionNotFoundException;
import nl.adg.qwixx.game.options.GameOptionCatalog;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.generated.api.GamesApiDelegate;
import nl.adg.qwixx.generated.model.AddPlayerToGame201ResponseDto;
import nl.adg.qwixx.generated.model.ColorDto;
import nl.adg.qwixx.generated.model.CreateNewGame201ResponseDto;
import nl.adg.qwixx.generated.model.GameInfoDto;
import nl.adg.qwixx.generated.model.GameStatusDto;
import nl.adg.qwixx.generated.model.NewGameRequestDto;
import nl.adg.qwixx.generated.model.PlayerDto;
import nl.adg.qwixx.generated.model.RestartGameRequestDto;
import nl.adg.qwixx.generated.model.ScoreCardDto;
import nl.adg.qwixx.generated.model.StartGameRequestDto;
import nl.adg.qwixx.rules.ScoreCard;
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

    @Autowired
    private BotTurnDriver botDriver;

    @Override
    public ResponseEntity<CreateNewGame201ResponseDto> createNewGame(NewGameRequestDto req) {
        String id = GameRegistry.createGame(req.getRoomName(), req.getMaxPlayers(),
                buildSettings(req.getGameOptions()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateNewGame201ResponseDto().sessionId(id));
    }

    @Override
    public ResponseEntity<List<GameInfoDto>> getAllGames() {
        return ResponseEntity.ok(GameRegistry.getAllGames().stream().map(this::toGameInfo).toList());
    }

    @Override
    public ResponseEntity<GameInfoDto> getGame(String sessionId) {
        return ResponseEntity.ok(toGameInfo(require(sessionId)));
    }

    @Override
    public ResponseEntity<Void> startGame(String sessionId,
            StartGameRequestDto req) {
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
    public ResponseEntity<Void> restartGame(String sessionId, RestartGameRequestDto req) {
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
        // Drive the opening bot turns off the request thread, waiting first for a subscriber so a
        // first-acting bot's dice animation isn't missed. Same paced, lock-yielding driver the move
        // endpoint uses, so it stays single-flight with any move that arrives.
        botDriver.ensureDriving(sessionId, session,
                intermediate -> sseRegistry.emit(sessionId, intermediate, session),
                () -> gameFinishedNotifier.checkAndNotify(sessionId, session),
                () -> awaitSubscriber(sessionId));
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
    public ResponseEntity<AddPlayerToGame201ResponseDto> addPlayerToGame(String sessionId,
            PlayerDto req) {
        GameSession session = require(sessionId);
        Player player = playerFromRequest(req);
        try {
            session.addPlayer(player);
        } catch (IllegalStateException ex) {
            throw new GameAlreadyStartedException(sessionId, ex);
        }
        lobbyController.emitLobby(sessionId, session);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AddPlayerToGame201ResponseDto().playerId(player.id().toString()));
    }

    @Override
    public ResponseEntity<List<PlayerDto>> getAllPlayersInGame(
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
    public ResponseEntity<Map<String, ScoreCardDto>> getScores(String sessionId) {
        GameSession session = require(sessionId);
        if (session.status() != SessionStatus.FINISHED)
            throw new GameNotFinishedException(sessionId);

        // Iterate the game-state player list (all who participated) rather than
        // session.players() which may be smaller if players left after the game ended.
        Map<String, ScoreCardDto> scores = session.currentState().players().stream()
                .collect(Collectors.toMap(UUID::toString, id -> toDto(session.getScore(id))));
        return ResponseEntity.ok(scores);
    }

    // ── Converters ────────────────────────────────────────────────────────────

    private static GameSettings buildSettings(@Nullable Map<String, Object> options) {
        GameSettings.Builder builder = GameSettings.builder();
        GameOptionCatalog.apply(builder, options);
        return builder.build();
    }

    private static Map<String, Object> resolveOptions(RestartGameRequestDto req, GameSession session) {
        Map<String, Object> options = req != null ? req.getGameOptions() : null;
        return options != null ? new HashMap<>(options) : session.proposedOptions();
    }

    private static Player playerFromRequest(PlayerDto req) {
        UUID id = req.getId() != null && !req.getId().isBlank()
                ? UUID.fromString(req.getId())
                : UUID.randomUUID();
        return new Player(id, req.getName(), req.getProfilePic());
    }

    private PlayerDto toPlayerDto(Player p) {
        return new PlayerDto(p.id().toString(), p.name())
                .profilePic(p.profilePic());
    }

    private static ScoreCardDto toDto(ScoreCard sc) {
        ScoreCardDto dto = new ScoreCardDto(
                toStringKeyedMap(sc.crossesPerColor()),
                toStringKeyedMap(sc.pointsPerColor()),
                sc.extraCrosses(), sc.extraPoints(),
                sc.bonusPoints(), sc.punishmentPoints(), sc.total());
        dto.setNoPenalty(sc.noPenalty());
        Color doubledColor = sc.doubledColor();
        if (doubledColor != null) {
            dto.setDoubledColor(ColorDto.fromValue(doubledColor.name()));
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

    private GameInfoDto toGameInfo(GameSession session) {
        return new GameInfoDto(
                session.sessionId(),
                session.roomName(),
                session.players().size(),
                session.maxPlayers(),
                toGameStatus(session));
    }

    private GameStatusDto toGameStatus(GameSession session) {
        return switch (session.status()) {
            case WAITING     -> GameStatusDto.WAITING;
            case IN_PROGRESS -> GameStatusDto.IN_PROGRESS;
            case FINISHED    -> GameStatusDto.FINISHED;
        };
    }
}
